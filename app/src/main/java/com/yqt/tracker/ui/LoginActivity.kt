package com.yqt.tracker.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yqt.tracker.databinding.ActivityLoginBinding
import com.yqt.tracker.network.Protocol
import com.yqt.tracker.network.YqtClient
import com.yqt.tracker.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val regions = Protocol.REGIONS.keys.toList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, regions)
        binding.regionInput.setAdapter(adapter)

        val saved = Prefs.load(this)
        if (saved != null) {
            binding.regionInput.setText(saved.first, false)
            binding.accountInput.setText(saved.second)
            binding.passwordInput.setText(saved.third)
        } else {
            binding.regionInput.setText("europe", false)
        }

        // If already have creds, try auto-login quickly? Optional - let user tap.
        binding.loginButton.setOnClickListener { doLogin() }
    }

    private fun doLogin() {
        val region = binding.regionInput.text?.toString()?.trim().orEmpty()
        val account = binding.accountInput.text?.toString()?.trim().orEmpty()
        val password = binding.passwordInput.text?.toString().orEmpty()

        var ok = true
        if (region.isEmpty()) { binding.regionInput.error = "Obligatorio"; ok = false }
        if (account.isEmpty()) { binding.accountInput.error = "Obligatorio"; ok = false }
        if (password.isEmpty()) { binding.passwordInput.error = "Obligatorio"; ok = false }
        if (!ok) return

        setLoading(true)
        binding.errorText.visibility = View.GONE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val client = YqtClient(this@LoginActivity, region, account, password)
                    client.login()
                    client.discoverDevices()
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            setLoading(false)
            result.onSuccess {
                Prefs.save(this@LoginActivity, region, account, password)
                startActivity(Intent(this@LoginActivity, MapActivity::class.java))
                finish()
            }.onFailure { e ->
                val msg = e.message ?: "Error"
                binding.errorText.text = msg
                binding.errorText.visibility = View.VISIBLE
                Toast.makeText(this@LoginActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.loginButton.isEnabled = !loading
    }
}
