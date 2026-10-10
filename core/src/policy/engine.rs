use serde::{Deserialize, Serialize};
use crate::error::CoreError;
use crate::policy::storage::{PolicySchedule, PolicyStorage};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum LockReason {
    ManualLock,
    TemporaryPause { remaining_seconds: u64 },
    ActiveSchedule { schedule_name: String, end_time: String },
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct DeviceLockStatus {
    pub is_locked: bool,
    pub reason: Option<LockReason>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum AppAccessDecision {
    Allowed { remaining_seconds_today: Option<u64> },
    BlockedByPolicy { reason: String },
    DailyQuotaExceeded { limit_minutes: u32, used_seconds: u64 },
    DeviceLocked { reason: LockReason },
}

pub struct PolicyEngine;

impl PolicyEngine {
    /// Whitelisted system packages that should never be blocked by normal lock policies (e.g., dialer for emergencies)
    pub const SYSTEM_WHITELIST: &'static [&'static str] = &[
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.phone",
        "com.android.emergency",
    ];

    pub fn is_system_whitelisted(package_name: &str) -> bool {
        Self::SYSTEM_WHITELIST.contains(&package_name)
    }

    /// Evaluates if the current time falls inside a schedule.
    /// Supports overnight windows (e.g. "21:00" to "06:00").
    pub fn is_time_in_range(start: &str, end: &str, current: &str) -> bool {
        if start <= end {
            current >= start && current < end
        } else {
            // Overnight window: e.g. 21:00 -> 06:00
            current >= start || current < end
        }
    }

    /// Evaluates whether a schedule is active given the current day of week (1=Mon..7=Sun) and HH:MM.
    pub fn is_schedule_active(schedule: &PolicySchedule, day_of_week: u8, current_hhmm: &str) -> bool {
        if !schedule.is_enabled {
            return false;
        }

        // Days bitmask check: 1 = Mon (1<<0), 2 = Tue (1<<1), ..., 7 = Sun (1<<6)
        if day_of_week < 1 || day_of_week > 7 {
            return false;
        }
        let day_mask = 1u8 << (day_of_week - 1);
        if (schedule.days_of_week & day_mask) == 0 {
            return false;
        }

        Self::is_time_in_range(&schedule.start_time, &schedule.end_time, current_hhmm)
    }

    /// Evaluates the device lock status based on manual lock, timed pause, and active schedules.
    pub fn evaluate_device_lock(
        storage: &PolicyStorage,
        current_time: u64,
        current_hhmm: &str,
        day_of_week: u8,
    ) -> Result<DeviceLockStatus, CoreError> {
        // 1. Check manual permanent lock
        if let Some(val) = storage.get_device_state("manual_lock")? {
            if val == "1" {
                return Ok(DeviceLockStatus {
                    is_locked: true,
                    reason: Some(LockReason::ManualLock),
                });
            }
        }

        // 2. Check temporary pause / lock
        if let Some(val) = storage.get_device_state("lock_until_epoch")? {
            if let Ok(until_epoch) = val.parse::<u64>() {
                if current_time < until_epoch {
                    let remaining = until_epoch - current_time;
                    return Ok(DeviceLockStatus {
                        is_locked: true,
                        reason: Some(LockReason::TemporaryPause {
                            remaining_seconds: remaining,
                        }),
                    });
                }
            }
        }

        // 3. Check active schedules (Bedtime / Study time)
        let schedules = storage.get_schedules()?;
        for sched in schedules {
            if Self::is_schedule_active(&sched, day_of_week, current_hhmm) {
                return Ok(DeviceLockStatus {
                    is_locked: true,
                    reason: Some(LockReason::ActiveSchedule {
                        schedule_name: sched.name,
                        end_time: sched.end_time,
                    }),
                });
            }
        }

        Ok(DeviceLockStatus {
            is_locked: false,
            reason: None,
        })
    }

    /// Evaluates whether a specific application is allowed to open / run right now.
    pub fn evaluate_app_access(
        storage: &PolicyStorage,
        package_name: &str,
        current_time: u64,
        current_hhmm: &str,
        day_of_week: u8,
        today_date_str: &str,
    ) -> Result<AppAccessDecision, CoreError> {
        // Emergency/phone dialer is always allowed
        if Self::is_system_whitelisted(package_name) {
            return Ok(AppAccessDecision::Allowed {
                remaining_seconds_today: None,
            });
        }

        // 1. Evaluate device-level lock
        let lock_status = Self::evaluate_device_lock(storage, current_time, current_hhmm, day_of_week)?;
        if lock_status.is_locked {
            return Ok(AppAccessDecision::DeviceLocked {
                reason: lock_status.reason.unwrap_or(LockReason::ManualLock),
            });
        }

        // 2. Evaluate app-specific limit or block
        if let Some(limit) = storage.get_app_limit(package_name)? {
            if limit.is_blocked {
                return Ok(AppAccessDecision::BlockedByPolicy {
                    reason: format!("App '{}' is blocked by parent policy", limit.app_name),
                });
            }

            if limit.daily_limit_minutes > 0 {
                let limit_seconds = (limit.daily_limit_minutes as u64) * 60;
                let used_seconds = if limit.last_reset_date == today_date_str {
                    limit.used_seconds_today
                } else {
                    0
                };

                if used_seconds >= limit_seconds {
                    return Ok(AppAccessDecision::DailyQuotaExceeded {
                        limit_minutes: limit.daily_limit_minutes,
                        used_seconds,
                    });
                } else {
                    let remaining = limit_seconds - used_seconds;
                    return Ok(AppAccessDecision::Allowed {
                        remaining_seconds_today: Some(remaining),
                    });
                }
            }
        }

        Ok(AppAccessDecision::Allowed {
            remaining_seconds_today: None,
        })
    }
}
