import SwiftUI

struct ContentView: View {
    @State private var botToken: String = "8915785873:AAF7xDSUaQxpbcLIIcnlCx4D1kC628oBpqk"
    @State private var chatId: String = "-5550682710"
    @State private var isSending: Bool = false
    @State private var statusMessage: String = "Ready"
    @State private var showSettings: Bool = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    // Status Badge
                    HStack {
                        Image(systemName: "shield.checkmark.fill")
                            .font(.system(size: 32))
                            .foregroundColor(.blue)
                        VStack(alignment: .leading) {
                            Text("SafeShield Parent")
                                .font(.title2.bold())
                            Text(statusMessage)
                                .font(.caption)
                                .foregroundColor(.gray)
                        }
                        Spacer()
                    }
                    .padding()
                    .background(Color(.secondarySystemBackground))
                    .cornerRadius(16)

                    // Quick Actions
                    VStack(alignment: .leading, spacing: 14) {
                        Text("Instant Controls")
                            .font(.headline)

                        HStack(spacing: 12) {
                            Button(action: { sendCommand(action: "LockNow", name: "Lock Device") }) {
                                Label("Lock Now", systemImage: "lock.fill")
                                    .font(.headline)
                                    .frame(maxWidth: .infinity)
                                    .padding()
                                    .background(Color.red)
                                    .foregroundColor(.white)
                                    .cornerRadius(12)
                            }
                            .disabled(isSending)

                            Button(action: { sendCommand(action: "UnlockNow", name: "Unlock Device") }) {
                                Label("Unlock", systemImage: "lock.open.fill")
                                    .font(.headline)
                                    .frame(maxWidth: .infinity)
                                    .padding()
                                    .background(Color.green)
                                    .foregroundColor(.white)
                                    .cornerRadius(12)
                            }
                            .disabled(isSending)
                        }

                        Text("Bonus Screen Time")
                            .font(.subheadline)
                            .foregroundColor(.gray)
                            .padding(.top, 6)

                        HStack(spacing: 10) {
                            ForEach([15, 30, 60], id: \.self) { mins in
                                Button(action: { sendBonusTime(minutes: mins) }) {
                                    Text("+\(mins) min")
                                        .font(.subheadline.bold())
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 10)
                                        .background(Color(.tertiarySystemBackground))
                                        .cornerRadius(8)
                                }
                            }
                        }
                    }
                    .padding()
                    .background(Color(.secondarySystemBackground))
                    .cornerRadius(16)

                    // Schedules
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Bedtime & Limits")
                            .font(.headline)

                        Button(action: { sendBedtimeSchedule() }) {
                            HStack {
                                Image(systemName: "moon.fill")
                                    .foregroundColor(.indigo)
                                VStack(alignment: .leading) {
                                    Text("Apply Night Bedtime")
                                        .font(.body.bold())
                                    Text("21:30 → 06:00 (Everyday)")
                                        .font(.caption)
                                        .foregroundColor(.gray)
                                }
                                Spacer()
                                Image(systemName: "arrow.right.circle.fill")
                                    .foregroundColor(.blue)
                            }
                            .padding()
                            .background(Color(.tertiarySystemBackground))
                            .cornerRadius(12)
                        }
                    }
                    .padding()
                    .background(Color(.secondarySystemBackground))
                    .cornerRadius(16)
                }
                .padding()
            }
            .navigationTitle("Parent SafeShield")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(action: { showSettings = true }) {
                        Image(systemName: "gearshape.fill")
                    }
                }
            }
            .sheet(isPresented: $showSettings) {
                NavigationStack {
                    Form {
                        Section("Telegram Settings") {
                            TextField("Bot Token", text: $botToken)
                            TextField("Chat ID", text: $chatId)
                        }
                    }
                    .navigationTitle("Configuration")
                    .toolbar {
                        Button("Done") { showSettings = false }
                    }
                }
            }
        }
    }

    private func sendCommand(action: String, name: String) {
        isSending = true
        statusMessage = "Sending \(name)..."

        let rawCmd = "CRT_CMD:{\"payload\":{\"command_id\":\"\(UUID().uuidString)\",\"sender_pubkey\":\"ios_parent\",\"target_device_id\":\"child_001\",\"timestamp\":\(Int(Date().timeIntervalSince1970)),\"expires_at\":\(Int(Date().timeIntervalSince1970) + 60),\"nonce\":\"\(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(16))\",\"action\":{\"type\":\"\(action)\"}},\"signature\":\"ios_sig\"}"

        postToTelegram(wireText: rawCmd, actionName: name)
    }

    private func sendBonusTime(minutes: Int) {
        let rawCmd = "CRT_CMD:{\"payload\":{\"command_id\":\"\(UUID().uuidString)\",\"sender_pubkey\":\"ios_parent\",\"target_device_id\":\"child_001\",\"timestamp\":\(Int(Date().timeIntervalSince1970)),\"expires_at\":\(Int(Date().timeIntervalSince1970) + 60),\"nonce\":\"\(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(16))\",\"action\":{\"type\":\"GrantBonusTime\",\"payload\":{\"package_name\":null,\"minutes\":\(minutes)}}},\"signature\":\"ios_sig\"}"

        postToTelegram(wireText: rawCmd, actionName: "Grant +\(minutes) min")
    }

    private func sendBedtimeSchedule() {
        let rawCmd = "CRT_CMD:{\"payload\":{\"command_id\":\"\(UUID().uuidString)\",\"sender_pubkey\":\"ios_parent\",\"target_device_id\":\"child_001\",\"timestamp\":\(Int(Date().timeIntervalSince1970)),\"expires_at\":\(Int(Date().timeIntervalSince1970) + 60),\"nonce\":\"\(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(16))\",\"action\":{\"type\":\"SetBedtimeSchedule\",\"payload\":{\"schedule_id\":\"bedtime_01\",\"name\":\"Night Bedtime\",\"days_of_week\":127,\"start_time\":\"21:30\",\"end_time\":\"06:00\",\"mode\":\"LOCK_ALL\"}}},\"signature\":\"ios_sig\"}"

        postToTelegram(wireText: rawCmd, actionName: "Night Bedtime Schedule")
    }

    private func postToTelegram(wireText: String, actionName: String) {
        guard let url = URL(string: "https://api.telegram.org/bot\(botToken)/sendMessage") else { return }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let jsonPayload: [String: Any] = [
            "chat_id": chatId,
            "text": wireText,
            "disable_notification": true
        ]

        request.httpBody = try? JSONSerialization.data(withJSONObject: jsonPayload)

        URLSession.shared.dataTask(with: request) { _, response, error in
            DispatchQueue.main.async {
                isSending = false
                if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                    statusMessage = "✓ \(actionName) Dispatched"
                } else {
                    statusMessage = "Error: \(error?.localizedDescription ?? "Failed")"
                }
            }
        }.resume()
    }
}
