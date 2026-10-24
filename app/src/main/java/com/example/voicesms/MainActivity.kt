package com.example.voicesms

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var input: EditText
    private lateinit var smsList: ListView
    private lateinit var contactLabel: TextView

    private data class Sms(val address: String, val body: String)
    private val messages = mutableListOf<Sms>()
    private var selected = -1
    private var contactNumber: String? = null
    private var replyMode = false

    private val REQ_PERM = 1
    private val REQ_STT = 2
    private val REQ_CONTACT = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tts = TextToSpeech(this, this)
        input = findViewById(R.id.inputText)
        smsList = findViewById(R.id.smsList)
        contactLabel = findViewById(R.id.contactLabel)

        ActivityCompat.requestPermissions(this, arrayOf(
            Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG,
            Manifest.permission.RECORD_AUDIO), REQ_PERM)

        findViewById<Button>(R.id.btnSpeak).setOnClickListener { speak(input.text.toString()) }
        findViewById<Button>(R.id.btnListen).setOnClickListener { replyMode = false; listen() }
        findViewById<Button>(R.id.btnLoadSms).setOnClickListener { loadSms() }
        smsList.setOnItemClickListener { _, _, pos, _ -> selected = pos
            Toast.makeText(this, "پیامک انتخاب شد", Toast.LENGTH_SHORT).show() }
        findViewById<Button>(R.id.btnReadSms).setOnClickListener {
            messages.getOrNull(selected)?.let { speak("پیام از ${it.address}. ${it.body}") }
                ?: toast("ابتدا یک پیامک انتخاب کنید")
        }
        findViewById<Button>(R.id.btnVoiceReply).setOnClickListener {
            if (messages.getOrNull(selected) == null) toast("ابتدا یک پیامک انتخاب کنید")
            else { replyMode = true; listen() }
        }
        findViewById<Button>(R.id.btnPickContact).setOnClickListener {
            if (has(Manifest.permission.READ_CONTACTS))
                startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), REQ_CONTACT)
            else toast("اجازه مخاطبین داده نشده")
        }
        findViewById<Button>(R.id.btnSend).setOnClickListener {
            val n = contactNumber; val t = input.text.toString()
            if (n == null || t.isBlank()) toast("مخاطب و متن لازم است") else confirmSend(n, t)
        }
        findViewById<Button>(R.id.btnCalls).setOnClickListener { showCalls() }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val fa = Locale("fa", "IR")
            if (tts.isLanguageAvailable(fa) < TextToSpeech.LANG_AVAILABLE)
                toast("صدای فارسی نصب نیست؛ در تنظیمات گوشی موتور TTS گوگل را با زبان فارسی نصب کنید")
            else tts.language = fa
        }
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun speak(t: String) { if (t.isBlank()) toast("متنی نیست") else tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "id") }

    private fun listen() {
        if (!has(Manifest.permission.RECORD_AUDIO)) { toast("اجازه میکروفن داده نشده"); return }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "بفرمایید...")
        }
        try { startActivityForResult(i, REQ_STT) } catch (e: Exception) { toast("تشخیص گفتار در این گوشی در دسترس نیست") }
    }

    private fun loadSms() {
        if (!has(Manifest.permission.READ_SMS)) { toast("اجازه پیامک داده نشده"); return }
        messages.clear()
        contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("address", "body"),
            null, null, "date DESC LIMIT 30")?.use { c ->
            while (c.moveToNext()) messages.add(Sms(c.getString(0) ?: "?", c.getString(1) ?: ""))
        }
        smsList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
            messages.map { "${it.address}: ${it.body.take(60)}" })
    }

    private fun showCalls() {
        if (!has(Manifest.permission.READ_CALL_LOG)) { toast("اجازه سابقه تماس داده نشده"); return }
        val items = mutableListOf<String>()
        contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE),
            null, null, "date DESC LIMIT 20")?.use { c ->
            while (c.moveToNext()) {
                val type = when (c.getInt(1)) { CallLog.Calls.INCOMING_TYPE -> "ورودی"
                    CallLog.Calls.OUTGOING_TYPE -> "خروجی"; CallLog.Calls.MISSED_TYPE -> "بی‌پاسخ"; else -> "" }
                items.add("${c.getString(0)} — $type")
            }
        }
        AlertDialog.Builder(this).setTitle("سابقه تماس‌ها")
            .setItems(items.toTypedArray(), null).setPositiveButton("بستن", null).show()
    }

    private fun confirmSend(number: String, text: String) {
        AlertDialog.Builder(this).setTitle("ارسال پیامک")
            .setMessage("به $number ارسال شود؟\n\n$text")
            .setPositiveButton("ارسال") { _, _ -> sendSms(number, text) }
            .setNegativeButton("لغو", null).show()
    }

    private fun sendSms(number: String, text: String) {
        if (!has(Manifest.permission.SEND_SMS)) { toast("اجازه ارسال پیامک داده نشده"); return }
        val sm = SmsManager.getDefault()
        sm.sendMultipartTextMessage(number, null, sm.divideMessage(text), null, null)
        toast("ارسال شد")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data == null) return
        when (requestCode) {
            REQ_STT -> {
                val text = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
                if (replyMode) {
                    val to = messages[selected].address
                    input.setText(text)
                    confirmSend(to, text)
                } else input.setText(text)
            }
            REQ_CONTACT -> {
                val uri: Uri = data.data ?: return
                contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) { contactNumber = c.getString(0)
                        contactLabel.text = "${c.getString(1)} (${c.getString(0)})" }
                }
            }
        }
    }

    override fun onDestroy() { tts.shutdown(); super.onDestroy() }
}
