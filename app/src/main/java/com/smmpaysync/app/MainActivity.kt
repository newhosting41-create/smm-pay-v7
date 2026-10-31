package com.smmpaysync.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var db: Db
    private lateinit var drawer: DrawerLayout
    private lateinit var nav: NavigationView
    private lateinit var etUrl: EditText
    private lateinit var etToken: EditText
    private lateinit var etSecret: EditText
    private lateinit var etSms: EditText
    private lateinit var spMethod: Spinner
    private lateinit var tvManualResult: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var currentPage = R.id.pageDashboard
    private var successSig = ""
    private var pendingSig = ""

    private val pageIds = intArrayOf(
        R.id.pageDashboard, R.id.pageManual, R.id.pageSuccess,
        R.id.pagePending, R.id.pageSettings, R.id.pageAbout
    )

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        db = Db.get(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        drawer = findViewById(R.id.drawer)
        nav = findViewById(R.id.nav)

        val toggle = ActionBarDrawerToggle(this, drawer, toolbar, R.string.nav_open, R.string.nav_close)
        drawer.addDrawerListener(toggle)
        toggle.syncState()
        toggle.drawerArrowDrawable.color = Color.WHITE

        nav.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> showPage(R.id.pageDashboard, "Dashboard")
                R.id.nav_manual -> showPage(R.id.pageManual, "Send SMS manually")
                R.id.nav_success -> showPage(R.id.pageSuccess, "Success / Used")
                R.id.nav_pending -> showPage(R.id.pagePending, "Pending / Not sent")
                R.id.nav_settings -> showPage(R.id.pageSettings, "Device settings")
                R.id.nav_about -> showPage(R.id.pageAbout, "About")
            }
            drawer.closeDrawer(GravityCompat.START)
            true
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawer.isDrawerOpen(GravityCompat.START)) {
                    drawer.closeDrawer(GravityCompat.START)
                } else if (currentPage != R.id.pageDashboard) {
                    nav.setCheckedItem(R.id.nav_dashboard)
                    showPage(R.id.pageDashboard, "Dashboard")
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // ---- settings page ----
        etUrl = findViewById(R.id.etUrl)
        etToken = findViewById(R.id.etToken)
        etSecret = findViewById(R.id.etSecret)
        etUrl.setText(prefs.url)
        etToken.setText(prefs.token)
        etSecret.setText(prefs.secret)

        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnPing).setOnClickListener { ping() }
        findViewById<Button>(R.id.btnPerm).setOnClickListener {
            if (hasSms()) {
                toast("SMS permission already granted")
            } else {
                requestNeededPermissions()
            }
        }
        findViewById<Button>(R.id.btnAppInfo).setOnClickListener {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            i.data = Uri.parse("package:$packageName")
            startActivity(i)
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                toast("Open phone Settings > Battery manually")
            }
        }
        findViewById<Button>(R.id.btnDisconnect).setOnClickListener {
            stopKeepAlive()
            prefs.clear()
            etUrl.setText("")
            etToken.setText("")
            etSecret.setText("")
            toast("Device disconnected")
            refresh()
        }

        // ---- always-on monitor switch ----
        val swKeep = findViewById<SwitchCompat>(R.id.swKeepAlive)
        swKeep.isChecked = prefs.keepAlive
        swKeep.setOnCheckedChangeListener { _, checked ->
            prefs.keepAlive = checked
            if (checked) startKeepAlive() else stopKeepAlive()
            refresh()
        }

        // ---- dashboard / pending buttons ----
        findViewById<Button>(R.id.btnSync).setOnClickListener {
            SyncWorker.enqueue(this)
            toast("Sync started")
            handler.postDelayed({ refresh() }, 3000)
        }
        findViewById<Button>(R.id.btnPendingSync).setOnClickListener {
            SyncWorker.enqueue(this)
            toast("Sync started")
            handler.postDelayed({ refresh() }, 3000)
        }
        findViewById<Button>(R.id.btnPendingRetry).setOnClickListener {
            db.retryFailed()
            SyncWorker.enqueue(this)
            toast("Retrying failed events")
            pendingSig = ""
            refresh()
        }

        // ---- manual page ----
        spMethod = findViewById(R.id.spMethod)
        etSms = findViewById(R.id.etSms)
        tvManualResult = findViewById(R.id.tvManualResult)
        spMethod.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("bKash", "Nagad (coming soon)")
        )
        findViewById<Button>(R.id.btnPasteSms).setOnClickListener { pasteFromClipboard() }
        findViewById<Button>(R.id.btnSendSms).setOnClickListener { sendManual() }

        // ---- about page ----
        findViewById<Button>(R.id.btnWebsite).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.website_url))))
            } catch (e: Exception) {
                toast(getString(R.string.website_url))
            }
        }
        try {
            val v = packageManager.getPackageInfo(packageName, 0).versionName
            findViewById<TextView>(R.id.tvVersion).text = "Version $v"
        } catch (e: Exception) {
        }

        Thread {
            try {
                db.purge(System.currentTimeMillis() - 30L * 24 * 3600 * 1000)
            } catch (e: Exception) {
            }
        }.start()

        SyncWorker.schedulePeriodic(this)

        showPage(R.id.pageDashboard, "Dashboard")

        requestNeededPermissions()
        startKeepAlive()
    }

    override fun onResume() {
        super.onResume()
        startKeepAlive()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ------------------------------------------------------------------ helpers

    private fun showPage(pageId: Int, title: String) {
        currentPage = pageId
        for (id in pageIds) {
            findViewById<View>(id).visibility = if (id == pageId) View.VISIBLE else View.GONE
        }
        supportActionBar?.title = title
        successSig = ""
        pendingSig = ""
        if (pageId == R.id.pageSuccess && prefs.isConfigured) {
            SyncWorker.enqueue(this) // also refreshes the "used on website" status
        }
        refresh()
    }

    private fun requestNeededPermissions() {
        val need = ArrayList<String>()
        if (!hasSms()) need.add(Manifest.permission.RECEIVE_SMS)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, need.toTypedArray(), 1)
        }
    }

    private fun startKeepAlive() {
        try {
            if (prefs.keepAlive && prefs.isConfigured) KeepAliveService.start(this)
        } catch (e: Exception) {
        }
    }

    private fun stopKeepAlive() {
        try {
            KeepAliveService.stop(this)
        } catch (e: Exception) {
        }
    }

    private fun hasSms(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        return caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun levelColor(level: Int): Int = when (level) {
        0 -> ContextCompat.getColor(this, R.color.green)
        1 -> ContextCompat.getColor(this, R.color.orange)
        2 -> ContextCompat.getColor(this, R.color.red)
        else -> ContextCompat.getColor(this, R.color.text_dark)
    }

    private fun pill(tv: TextView, color: Int) {
        tv.setTextColor(color)
        val bg = GradientDrawable()
        bg.cornerRadius = 100f
        bg.setColor(Color.argb(38, Color.red(color), Color.green(color), Color.blue(color)))
        tv.background = bg
    }

    // ------------------------------------------------------------------ settings

    private fun save() {
        val url = etUrl.text.toString().trim()
        val token = etToken.text.toString().trim()
        val secret = etSecret.text.toString().trim()
        if (!url.startsWith("https://")) {
            toast("URL must start with https://")
            return
        }
        if (token.isEmpty() || secret.isEmpty()) {
            toast("Token and Secret are required")
            return
        }
        prefs.url = url
        prefs.token = token
        prefs.secret = secret
        prefs.lastResult = ""
        toast("Saved")
        startKeepAlive()
        refresh()
    }

    private fun ping() {
        if (!prefs.isConfigured) {
            toast("Save URL, Token and Secret first")
            return
        }
        toast("Testing connection...")
        Thread {
            val msg = try {
                val r = Http.post(prefs.url, prefs.token, prefs.secret, "{\"action\":\"ping\"}")
                if (r.first == 200) {
                    prefs.lastResult = "OK"
                    "Connected successfully"
                } else {
                    prefs.lastResult = "HTTP ${r.first}"
                    "Failed: HTTP ${r.first} ${r.second.take(100)}"
                }
            } catch (e: Exception) {
                prefs.lastResult = "Network error"
                "Error: ${e.message}"
            }
            runOnUiThread {
                toast(msg)
                refresh()
            }
        }.start()
    }

    // ------------------------------------------------------------------ manual send

    private fun manualResult(msg: String, level: Int) {
        tvManualResult.text = msg
        tvManualResult.setTextColor(levelColor(level))
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
        if (t.isBlank()) {
            toast("Clipboard খালি")
        } else {
            etSms.setText(t)
        }
    }

    private fun sendManual() {
        if (spMethod.selectedItemPosition != 0) {
            manualResult("Nagad এখনো সার্ভারে যুক্ত হয়নি। আপাতত শুধু bKash পাঠানো যাবে।", 1)
            return
        }
        if (!prefs.isConfigured) {
            manualResult("আগে Device settings এ URL, Token ও Secret সেভ করুন।", 2)
            return
        }
        val text = etSms.text.toString().trim()
        if (text.isEmpty()) {
            manualResult("মেসেজ বক্স খালি। আগে SMS পেস্ট করুন।", 2)
            return
        }
        val p = BkashParser.parse(text)
        if (p == null) {
            manualResult(
                "মেসেজের ফরম্যাট মেলেনি। আসল bKash \"You have received Tk ...\" মেসেজটি পুরোটা, বাড়তি কিছু ছাড়া পেস্ট করুন।",
                2
            )
            return
        }

        val inserted = db.insert(
            Ev(
                localId = UUID.randomUUID().toString(),
                trxId = p.trxId,
                amount = p.amount,
                sender = p.sender,
                smsTime = p.smsTime,
                raw = text,
                status = "PENDING",
                error = null,
                createdAt = System.currentTimeMillis()
            )
        )
        if (!inserted) {
            val requeued = db.requeue(p.trxId, text)
            if (!requeued) {
                val st = db.statusOfTrx(p.trxId) ?: "?"
                manualResult("এই TrxID (${p.trxId}) আগেই আছে। অবস্থা: $st", 1)
                return
            }
        }

        SyncWorker.enqueue(this)
        etSms.setText("")
        val amountText = String.format(Locale.US, "Tk %.2f", p.amount)
        if (isOnline()) {
            manualResult("পাঠানো হচ্ছে: ${p.trxId} ($amountText)। ফলাফল Success বা Pending পেজে দেখুন।", 0)
        } else {
            manualResult("ইন্টারনেট নেই। ${p.trxId} ($amountText) Pending এ সংরক্ষিত, নেট এলে নিজে থেকে সার্ভারে যাবে।", 1)
        }
        handler.postDelayed({ refresh() }, 3000)
    }

    // ------------------------------------------------------------------ refresh

    private fun refresh() {
        when (currentPage) {
            R.id.pageDashboard -> refreshDashboard()
            R.id.pageSuccess -> refreshSuccess()
            R.id.pagePending -> refreshPending()
        }
    }

    private fun row(box: LinearLayout, label: String, value: String, level: Int) {
        val line = LinearLayout(this)
        line.orientation = LinearLayout.HORIZONTAL
        line.setPadding(0, 9, 0, 9)

        val l = TextView(this)
        l.text = label
        l.textSize = 14f
        l.setTextColor(ContextCompat.getColor(this, R.color.grey))
        l.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        val v = TextView(this)
        v.text = value
        v.textSize = 14f
        v.gravity = Gravity.END
        v.setTypeface(null, Typeface.BOLD)
        v.setTextColor(levelColor(level))
        v.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f)

        line.addView(l)
        line.addView(v)
        box.addView(line)
    }

    private fun refreshDashboard() {
        val sms = hasSms()
        val configured = prefs.isConfigured
        val active = sms && configured

        val tvMonitor = findViewById<TextView>(R.id.tvMonitor)
        tvMonitor.text = if (active) "●  MONITOR ACTIVE" else "●  MONITOR INACTIVE"
        val pillBg = GradientDrawable()
        pillBg.cornerRadius = 100f
        pillBg.setColor(if (active) Color.argb(70, 255, 255, 255) else Color.argb(160, 198, 40, 40))
        tvMonitor.background = pillBg

        findViewById<TextView>(R.id.tvStatUsed).text = db.count("status='USED'").toString()
        findViewById<TextView>(R.id.tvStatSynced).text =
            db.count("status IN ('SYNCED','DUPLICATE')").toString()
        findViewById<TextView>(R.id.tvStatPending).text = db.count("status='PENDING'").toString()
        findViewById<TextView>(R.id.tvStatAttention).text =
            db.count("status IN ('FAILED','REJECTED','FLAGGED')").toString()

        val box = findViewById<LinearLayout>(R.id.statusBox)
        box.removeAllViews()

        row(box, "SMS permission", if (sms) "Granted" else "Missing", if (sms) 0 else 2)
        row(box, "Device pairing", if (configured) "Configured" else "Not configured", if (configured) 0 else 2)

        val online = isOnline()
        row(box, "Internet", if (online) "Connected" else "Offline", if (online) 0 else 1)

        val res = prefs.lastResult
        row(
            box, "Backend",
            if (res.isEmpty()) "Not tested yet" else res,
            if (res == "OK") 0 else if (res.isEmpty()) 1 else 2
        )

        val keep = prefs.keepAlive
        row(box, "Always-on monitor", if (keep) "On" else "Off (less reliable)", if (keep) 0 else 1)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val unrestricted = pm.isIgnoringBatteryOptimizations(packageName)
        row(box, "Battery", if (unrestricted) "Unrestricted" else "May be limited", if (unrestricted) 0 else 1)

        val last = prefs.lastSync
        row(
            box, "Last successful sync",
            if (last == 0L) "Never"
            else SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(Date(last)),
            if (last == 0L) 1 else 0
        )
    }

    private fun badge(status: String): Pair<String, Int> = when (status) {
        "USED" -> Pair("USED ON WEBSITE", R.color.green)
        "SYNCED" -> Pair("SYNCED - WAITING FOR CLIENT", R.color.indigo)
        "DUPLICATE" -> Pair("DUPLICATE (ALREADY ON SERVER)", R.color.grey)
        "PENDING" -> Pair("PENDING - NOT SENT YET", R.color.orange)
        "FLAGGED" -> Pair("UNDER REVIEW", R.color.orange)
        "REJECTED" -> Pair("REJECTED BY SERVER", R.color.red)
        "FAILED" -> Pair("FAILED - CHECK TOKEN/SECRET", R.color.red)
        else -> Pair(status, R.color.grey)
    }

    private fun addEventCard(parent: LinearLayout, e: Ev) {
        val v = layoutInflater.inflate(R.layout.item_event, parent, false)
        val b = badge(e.status)
        val color = ContextCompat.getColor(this, b.second)

        v.findViewById<View>(R.id.vStrip).setBackgroundColor(color)
        v.findViewById<TextView>(R.id.tvTrx).text = e.trxId
        v.findViewById<TextView>(R.id.tvAmount).text = String.format(Locale.US, "Tk %.2f", e.amount)
        v.findViewById<TextView>(R.id.tvMeta).text = "From ${e.sender}  •  SMS time ${e.smsTime}"

        val badgeView = v.findViewById<TextView>(R.id.tvBadge)
        badgeView.text = b.first
        pill(badgeView, color)

        val err = v.findViewById<TextView>(R.id.tvErr)
        if (!e.error.isNullOrEmpty()) {
            err.text = e.error
            err.visibility = View.VISIBLE
        }
        parent.addView(v)
    }

    private fun fillList(listId: Int, emptyId: Int, events: List<Ev>) {
        val list = findViewById<LinearLayout>(listId)
        list.removeAllViews()
        findViewById<View>(emptyId).visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
        for (e in events) addEventCard(list, e)
    }

    private fun refreshSuccess() {
        val events = db.byStatus(listOf("USED", "SYNCED", "DUPLICATE"), 100)
        findViewById<TextView>(R.id.tvSuccessCount).text = "${events.size} payment(s)"
        val sig = events.joinToString("|") { it.localId + it.status }
        if (sig == successSig) return
        successSig = sig
        fillList(R.id.successList, R.id.tvSuccessEmpty, events)
    }

    private fun refreshPending() {
        val events = db.byStatus(listOf("PENDING", "FAILED", "REJECTED", "FLAGGED"), 100)
        findViewById<TextView>(R.id.tvPendingCount).text = "${events.size} payment(s)"
        val sig = events.joinToString("|") { it.localId + it.status + (it.error ?: "") }
        if (sig == pendingSig) return
        pendingSig = sig
        fillList(R.id.pendingList, R.id.tvPendingEmpty, events)
    }
}
