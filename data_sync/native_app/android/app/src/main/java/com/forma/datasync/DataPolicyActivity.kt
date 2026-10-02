package com.forma.datasync

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/** Public rationale required by Health Connect permission controls. */
class DataPolicyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val explanation = TextView(this).apply {
            textSize = 17f
            setPadding(36, 48, 36, 48)
            text = """
                Forma Data Connect — data permissions

                You choose the server and which sources Android allows this app to read. After you accept consent in onboarding, granted data is uploaded to that server with your account and a random device identifier. The server operator controls retention of uploaded data.

                Usage Access allows app foreground summaries, activity and screen/unlock events, and network usage. Calendar permission reads event titles, times and locations. Location permission reads available location snapshots; background location enables those snapshots while the UI is closed. Activity recognition permits a brief step-counter sensor snapshot, which is not a full activity history.

                Health Connect lets you select steps, sleep and stages, workouts, calories, nutrition, hydration, heart and respiratory measures, blood pressure/glucose/oxygen, temperature, body measurements, reproductive health, and other supported categories. We read only granted records already stored in Health Connect. Optional background and history permissions allow background reads and records older than 30 days, where supported. No health records are written.

                Hourly uploads are scheduled on the phone using Android WorkManager and may be delayed by power management or lack of a network. No private messages, passwords, other applications' private files, or lifetime device history are read.

                Your password is sent only at sign-in. The session credential and pending upload are protected using Android Keystore encryption, excluded from backup, and removed on sign-out. Uploads require HTTPS in release builds.

                Pause uploads in the dashboard data controls. Review or revoke sources in Android app permissions, Usage Access or Health Connect settings. Sign out to cancel future work and clear this app's local connection. Permission revocation and sign-out do not delete data already received by your server; contact that server's operator to request deletion.
            """.trimIndent()
        }
        setContentView(ScrollView(this).apply { addView(explanation) })
    }
}
