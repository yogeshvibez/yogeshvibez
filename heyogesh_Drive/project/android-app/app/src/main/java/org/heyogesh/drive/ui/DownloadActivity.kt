package org.heyogesh.drive.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import org.heyogesh.drive.databinding.ActivityDownloadBinding
import org.heyogesh.drive.downloads.DownloadRepository
import org.heyogesh.drive.downloads.DownloadService

class DownloadActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDownloadBinding
    private lateinit var repository: DownloadRepository
    private val handler = Handler(Looper.getMainLooper())
    private var receiverRegistered = false
    private val refreshRunnable = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 1_000) }
    }
    private val updates = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = DownloadRepository(applicationContext)
        binding.downloadList.layoutManager = LinearLayoutManager(this)
        binding.downloadList.adapter = DownloadAdapter(repository::pause, repository::resume, repository::cancel)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, updates, IntentFilter(DownloadService.ACTION_UPDATED), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        handler.post(refreshRunnable)
    }

    override fun onStop() {
        handler.removeCallbacks(refreshRunnable)
        if (receiverRegistered) unregisterReceiver(updates)
        receiverRegistered = false
        super.onStop()
    }

    private fun render() {
        val records = repository.records()
        (binding.downloadList.adapter as DownloadAdapter).submit(records)
        binding.emptyDownloads.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
    }
}
