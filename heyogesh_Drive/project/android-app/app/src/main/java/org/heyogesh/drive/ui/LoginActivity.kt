package org.heyogesh.drive.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import org.heyogesh.drive.api.DriveApi
import org.heyogesh.drive.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var api: DriveApi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = DriveApi(applicationContext)
        if (api.sessionStore().getValid() != null) {
            openDrive()
            return
        }
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.unlockButton.setOnClickListener { authenticate() }
        binding.passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { authenticate(); true } else false
        }
    }

    private fun authenticate() {
        val password = binding.passwordInput.text?.toString().orEmpty()
        if (password.isBlank()) {
            binding.passwordLayout.error = "Enter the storage password."
            return
        }
        binding.passwordLayout.error = null
        setLoading(true)
        lifecycleScope.launch {
            try {
                val session = api.login(password)
                api.sessionStore().save(session.accessToken, DriveApi.expiresAtMillis(session.expiresAt))
                openDrive()
            } catch (error: Exception) {
                binding.passwordLayout.error = error.message ?: "Could not connect to storage."
                Snackbar.make(binding.root, "Check your connection and password.", Snackbar.LENGTH_LONG).show()
                setLoading(false)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.loginProgress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.unlockButton.isEnabled = !loading
        binding.passwordInput.isEnabled = !loading
    }

    private fun openDrive() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
