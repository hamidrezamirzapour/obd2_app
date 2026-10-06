package ir.hamidreza.autodiag

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var socket: BluetoothSocket? = null
    private var pollTask: ScheduledFuture<*>? = null
    private var adapter: BluetoothAdapter? = null
    private val devices = mutableListOf<BluetoothDevice>()
    private lateinit var devicePicker: Spinner
    private lateinit var status: TextView
    private lateinit var telemetry: TextView
    private lateinit var vehicleInput: EditText
    private lateinit var odometerInput: EditText
    private lateinit var nextServiceInput: EditText
    private lateinit var reminderStatus: TextView
    private val permissionRequest = 701

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        adapter = manager.adapter
        buildUi()
        loadReminder()
        refreshPairedDevices()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 22, 24, 24)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            textDirection = View.TEXT_DIRECTION_RTL
        }
        scroll.addView(column)
        setContentView(scroll)
        fun label(text: String, size: Float = 16f): TextView = TextView(this).apply {
            this.text = text; textSize = size; setPadding(0, 10, 0, 8)
        }
        fun button(text: String, action: () -> Unit): Button = Button(this).apply {
            this.text = text; setOnClickListener { action() }
        }
        column.addView(label("همراه خودرو", 24f))
        column.addView(label("اتصال فقط به دستگاه‌های بلوتوث جفت‌شده (Classic)", 14f))
        devicePicker = Spinner(this)
        column.addView(devicePicker)
        column.addView(button("به‌روزرسانی دستگاه‌های جفت‌شده") { refreshPairedDevices() })
        val connectionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        connectionRow.addView(button("اتصال") { connectSelected() }, LinearLayout.LayoutParams(0, -2, 1f))
        connectionRow.addView(button("قطع اتصال") { disconnect("اتصال قطع شد") }, LinearLayout.LayoutParams(0, -2, 1f))
        column.addView(connectionRow)
        status = label("وضعیت: آماده")
        column.addView(status)
        column.addView(label("اطلاعات زنده موتور (وابسته به خودرو و ECU)", 18f))
        telemetry = label("داده‌ای دریافت نشده است.")
        column.addView(telemetry)
        column.addView(button("خواندن کدهای خطا (Mode 03)") { scanCodes() })
        column.addView(button("پاک‌کردن کدها (Mode 04)") { confirmClear() })
        column.addView(label("یادآور سرویس (ذخیره روی همین گوشی)", 18f))
        vehicleInput = edit("نام خودرو")
        odometerInput = edit("کیلومتر فعلی")
        nextServiceInput = edit("کیلومتر سرویس بعدی")
        column.addView(vehicleInput); column.addView(odometerInput); column.addView(nextServiceInput)
        column.addView(button("ذخیره یادآور") { saveReminder() })
        reminderStatus = label("")
        column.addView(reminderStatus)
    }

    private fun edit(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        textDirection = View.TEXT_DIRECTION_LOCALE
        inputType = if (hintText == "نام خودرو") InputType.TYPE_CLASS_TEXT else InputType.TYPE_CLASS_NUMBER
        setSingleLine(true)
    }

    private fun setStatus(message: String) { runOnUiThread { if (::status.isInitialized) status.text = "وضعیت: $message" } }
    private fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun requireConnectPermission(): Boolean {
        if (hasConnectPermission()) return true
        if (Build.VERSION.SDK_INT >= 31) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), permissionRequest)
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == permissionRequest) {
            if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) refreshPairedDevices()
            else setStatus("برای مشاهده دستگاه‌های جفت‌شده، مجوز بلوتوث لازم است.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshPairedDevices() {
        if (!requireConnectPermission()) return
        val bt = adapter
        if (bt == null) { setStatus("این دستگاه بلوتوث ندارد."); return }
        if (!bt.isEnabled) { setStatus("بلوتوث خاموش است؛ آن را از تنظیمات روشن کنید."); return }
        try {
            devices.clear()
            devices.addAll(bt.bondedDevices.sortedBy { it.name ?: it.address })
            val names = devices.map { "${it.name ?: "دستگاه بی‌نام"} — ${it.address}" }
            devicePicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names.ifEmpty { listOf("دستگاه جفت‌شده‌ای یافت نشد") })
            setStatus(if (devices.isEmpty()) "ابتدا دانگل را در تنظیمات Android جفت کنید." else "دستگاه‌های جفت‌شده آماده انتخاب‌اند.")
        } catch (e: SecurityException) { setStatus("مجوز بلوتوث داده نشده است.") }
    }

    @SuppressLint("MissingPermission")
    private fun connectSelected() {
        if (!requireConnectPermission()) return
        if (adapter?.isEnabled != true) { setStatus("بلوتوث خاموش است؛ از تنظیمات روشن کنید."); return }
        val index = devicePicker.selectedItemPosition
        if (index !in devices.indices) { setStatus("یک دانگل جفت‌شده انتخاب کنید."); return }
        val device = devices[index]
        setStatus("در حال اتصال به ${device.name ?: device.address}…")
        worker.execute {
            closeSocket()
            try {
                val candidate = device.createRfcommSocketToServiceRecord(sppUuid)
                socket = candidate
                candidate.connect()
                val init = listOf("ATZ", "ATE0", "ATL0", "ATS1", "ATH0", "ATSP0")
                for (cmd in init) sendCommand(cmd)
                setStatus("متصل شد؛ آماده خواندن داده‌های استاندارد موتور.")
                runOnUiThread { telemetry.text = "در حال دریافت…" }
                startPolling()
            } catch (e: Exception) {
                closeSocket()
                setStatus("اتصال برقرار نشد: ${friendly(e)}")
            }
        }
    }

    private fun startPolling() {
        pollTask?.cancel(false)
        pollTask = worker.scheduleWithFixedDelay({
            if (socket == null) return@scheduleWithFixedDelay
            try {
                val rpm = pid("010C") { b -> if (b.size >= 2) ((b[0] * 256 + b[1]) / 4.0).toInt().toString() + " دور/دقیقه" else null }
                val speed = pid("010D") { b -> b.firstOrNull()?.let { "$it کیلومتر/ساعت" } }
                val coolant = pid("0105") { b -> b.firstOrNull()?.let { "${it - 40} °C" } }
                val text = "دور موتور: ${rpm ?: "دریافت نشد/پشتیبانی نامشخص"}
سرعت: ${speed ?: "دریافت نشد/پشتیبانی نامشخص"}
دمای مایع خنک‌کننده: ${coolant ?: "دریافت نشد/پشتیبانی نامشخص"}"
                runOnUiThread { telemetry.text = text }
            } catch (e: Exception) { setStatus("خطا در خواندن داده؛ اتصال را بررسی کنید.") }
        }, 0, 2, TimeUnit.SECONDS)
    }

    private fun pid(command: String, decode: (List<Int>) -> String?): String? {
        val response = sendCommand(command)
        if (response.contains("NO DATA", true) || response.contains("?")) return null
        val bytes = hexBytes(response)
        val marker = if (command == "010C") 0x41 else 0x41
        val pidByte = command.substring(2).toInt(16)
        val at = bytes.indices.firstOrNull { i -> i + 1 < bytes.size && bytes[i] == marker && bytes[i + 1] == pidByte } ?: return null
        return decode(bytes.drop(at + 2))
    }

    private fun scanCodes() {
        if (socket == null) { setStatus("ابتدا به دانگل متصل شوید."); return }
        setStatus("در حال خواندن کدهای عمومی OBD-II…")
        worker.execute {
            try {
                val response = sendCommand("03")
                val display = when {
                    response.contains("NO DATA", true) || response.contains("UNABLE", true) || response.contains("?", true) ->
                        "پاسخ معتبر دریافت نشد یا این فرمان پشتیبانی نشد؛ نبود کد خطا تأیید نشده است.
پاسخ: $response"
                    else -> {
                        val bytes = hexBytes(response)
                        val marker = bytes.indexOf(0x43)
                        if (marker < 0) "پاسخ تأییدشده Mode 03 پیدا نشد؛ نتیجه نامشخص است.
پاسخ: $response"
                        else {
                            val codes = mutableListOf<String>()
                            var i = marker + 1
                            while (i + 1 < bytes.size) {
                                val a = bytes[i]; val b = bytes[i + 1]
                                if (a == 0 && b == 0) { i += 2; continue }
                                val prefix = listOf('P','C','B','U')[(a shr 6) and 3]
                                val code = "%c%d%X%X".format(Locale.US, prefix, (a shr 4) and 3, a and 15, (b shr 4) and 15)
                                codes.add("$code — ${explanation(code)}")
                                i += 2
                            }
                            if (codes.isEmpty()) "پاسخ Mode 03 دریافت شد و هیچ کدی گزارش نشد. این نتیجه فقط به ECUهای پاسخ‌گو و دامنه عمومی OBD-II مربوط است."
                            else "کدهای گزارش‌شده:
" + codes.joinToString("
")
                        }
                    }
                }
                runOnUiThread { AlertDialog.Builder(this).setTitle("نتیجه بررسی کدها").setMessage(display).setPositiveButton("باشه", null).show() }
                setStatus("بررسی Mode 03 پایان یافت.")
            } catch (e: Exception) { setStatus("خواندن کدها ناموفق بود: ${friendly(e)}") }
        }
    }

    private fun explanation(code: String): String = when (code) {
        "P0100" -> "مدار/سیگنال اندازه‌گیری جریان هوا (MAF)"
        "P0101" -> "محدوده یا عملکرد MAF"
        "P0115" -> "مدار دمای مایع خنک‌کننده موتور"
        "P0128" -> "دمای موتور پایین‌تر از حد مورد انتظار ترموستات"
        "P0300" -> "احتراق ناقص تصادفی/چند سیلندر"
        "P0420" -> "کارایی کاتالیزور پایین‌تر از آستانه"
        "P0440" -> "سامانه کنترل بخارات سوخت (EVAP)"
        "P0500" -> "سیگنال سرعت خودرو"
        else -> "شرح عمومی در دسترس نیست؛ برای تفسیر دقیق، خودرو را تخصصی بررسی کنید."
    }

    private fun confirmClear() {
        if (socket == null) { setStatus("ابتدا به دانگل متصل شوید."); return }
        AlertDialog.Builder(this)
            .setTitle("پاک‌کردن کدها؟")
            .setMessage("فرمان عمومی Mode 04 ممکن است کدها و داده‌های آمادگی آلایندگی (readiness) را پاک کند. پاک‌کردن علت خرابی را تعمیر نمی‌کند و ممکن است عیب دوباره ظاهر شود. فقط در صورت اطمینان ادامه دهید.")
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ارسال فرمان پاک‌کردن") { _, _ -> clearCodes() }
            .show()
    }

    private fun clearCodes() {
        worker.execute {
            try {
                val response = sendCommand("04")
                val message = if (hexBytes(response).contains(0x44))
                    "ECU پاسخ مثبت Mode 04 داد. این به‌معنای تعمیر علت نیست و پاک‌شدن همه کدهای همه ماژول‌ها تضمین نمی‌شود. آمادگی آلایندگی ممکن است نیاز به رانندگی/آزمون مجدد داشته باشد."
                else "فرمان ارسال شد، اما پاسخ، پاک‌شدن را تأیید نکرد (یا پشتیبانی نشد). علت خرابی تعمیر نمی‌شود و ممکن است داده آمادگی پاک شود.
پاسخ: $response"
                runOnUiThread { AlertDialog.Builder(this).setTitle("نتیجه فرمان").setMessage(message).setPositiveButton("باشه", null).show() }
            } catch (e: Exception) { setStatus("ارسال فرمان ناموفق بود: ${friendly(e)}") }
        }
    }

    @Throws(IOException::class)
    private fun sendCommand(command: String): String {
        val current = socket ?: throw IOException("اتصالی وجود ندارد")
        current.outputStream.write((command + "\r").toByteArray(Charsets.US_ASCII))
        current.outputStream.flush()
        val input = current.inputStream
        val result = StringBuilder()
        val deadline = System.currentTimeMillis() + 4500
        var prompt = false
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val value = input.read()
                if (value >= 0) {
                    val ch = value.toChar()
                    if (ch == '>') { prompt = true; break }
                    result.append(ch)
                }
            } else {
                if (result.isNotEmpty() && System.currentTimeMillis() + 800 >= deadline) break
                Thread.sleep(35)
            }
        }
        if (!prompt && result.isEmpty()) throw IOException("پاسخی از دانگل دریافت نشد")
        return result.toString().replace("\r", "
").trim()
    }

    private fun hexBytes(text: String): List<Int> = Regex("(?i)(?<![0-9A-F])[0-9A-F]{2}(?![0-9A-F])")
        .findAll(text.replace("\r", " ").replace("\n", " ")).mapNotNull { it.value.toIntOrNull(16) }.toList()

    private fun disconnect(message: String) {
        pollTask?.cancel(false)
        worker.execute { closeSocket(); setStatus(message) }
    }

    private fun closeSocket() {
        pollTask?.cancel(false)
        pollTask = null
        try { socket?.close() } catch (_: Exception) { }
        socket = null
    }

    private fun friendly(e: Exception): String = when (e) {
        is SecurityException -> "مجوز بلوتوث لازم است"
        is IOException -> "دستگاه در دسترس نیست یا پاسخ نداد"
        else -> e.localizedMessage ?: "خطای نامشخص"
    }

    private fun loadReminder() {
        val prefs = getSharedPreferences("service_reminder", MODE_PRIVATE)
        vehicleInput.setText(prefs.getString("vehicle", "") ?: "")
        odometerInput.setText(prefs.getString("odo", "") ?: "")
        nextServiceInput.setText(prefs.getString("next", "") ?: "")
        updateReminderStatus()
    }

    private fun saveReminder() {
        val vehicle = vehicleInput.text.toString().trim()
        val odoText = odometerInput.text.toString().trim()
        val nextText = nextServiceInput.text.toString().trim()
        val odo = odoText.toLongOrNull(); val next = nextText.toLongOrNull()
        if (odo == null || next == null || odo < 0 || next < 0) {
            reminderStatus.text = "کیلومتر فعلی و سرویس بعدی را به‌صورت عدد صحیح نامنفی وارد کنید."
            return
        }
        getSharedPreferences("service_reminder", MODE_PRIVATE).edit()
            .putString("vehicle", vehicle).putString("odo", odoText).putString("next", nextText).apply()
        updateReminderStatus()
    }

    private fun updateReminderStatus() {
        if (!::reminderStatus.isInitialized) return
        val name = vehicleInput.text.toString().trim().ifEmpty { "خودرو" }
        val odo = odometerInput.text.toString().toLongOrNull()
        val next = nextServiceInput.text.toString().toLongOrNull()
        reminderStatus.text = when {
            odo == null || next == null -> "برای نمایش وضعیت، کیلومترها را وارد و ذخیره کنید."
            odo >= next -> "$name: موعد سرویس رسیده یا گذشته است."
            else -> "$name: حدود ${next - odo} کیلومتر تا سرویس بعدی باقی است."
        }
    }

    override fun onDestroy() {
        pollTask?.cancel(true)
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        worker.shutdownNow()
        super.onDestroy()
    }
}
