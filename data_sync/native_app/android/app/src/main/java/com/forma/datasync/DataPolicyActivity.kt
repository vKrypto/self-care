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

                You choose which sources Android allows this app to read. You can skip login and collect permitted data locally without a server. After you sign in and accept upload consent in connection settings, queued data is uploaded to your chosen server with your account and a random device identifier. The server operator controls retention of uploaded data.

                Usage Access allows app foreground summaries, activity and screen/unlock events, and network usage. Calendar permission reads event titles, times and locations. Location permission reads available location snapshots; background location enables those snapshots while the UI is closed. Activity recognition permits a brief step-counter sensor snapshot, which is not a full activity history.

                Health Connect lets you select steps, sleep and stages, workouts, calories, nutrition, hydration, heart and respiratory measures, blood pressure/glucose/oxygen, temperature, body measurements, reproductive health, and other supported categories. We read only granted records already stored in Health Connect. Optional background and history permissions allow background reads and records older than 30 days, where supported. No health records are written.

                Hourly collection is scheduled on the phone using Android WorkManager and does not require a network. Uploads are scheduled separately and require a connected account and network. Power management may delay background jobs. No private messages, passwords, other applications' private files, or lifetime device history are read.

                Your password is sent only at sign-in. The session credential, local data queue and sync history are protected using Android Keystore encryption and excluded from backup. Signing out removes the session credential and preserves local data. The encrypted queue is limited to 64 MiB; if it fills, collection waits for space and reports an error instead of deleting unuploaded records. Acknowledged uploads leave the queue and remain viewable in a separate encrypted history archive for up to 30 days or 64 MiB. The latest 500 finished job summaries, plus all queued jobs, remain visible even when older synced records are no longer stored locally. Uploads require HTTPS in release builds.

                Pause collection and uploads separately in the dashboard data controls. Review or revoke sources in Android app permissions, Usage Access or Health Connect settings. Sign out to stop uploads; permitted local collection continues unless you pause it. Queued records belonging to an earlier account are kept separate and cannot upload to a different account. Permission revocation blocks affected queued uploads. Uninstalling or clearing app storage deletes local records. Permission revocation and sign-out do not delete data already received by your server; contact that server's operator to request deletion.
            """.trimIndent()
        }
        setContentView(ScrollView(this).apply { addView(explanation) })
    }
}
