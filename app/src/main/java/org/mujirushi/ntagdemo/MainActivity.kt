package org.mujirushi.ntagdemo

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentFilter.MalformedMimeTypeException
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcA
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.experimental.and

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private lateinit var mAdapter: NfcAdapter
        private lateinit var mPendingIntent: PendingIntent
        private lateinit var mFilters: Array<IntentFilter>
        private lateinit var mTechLists: Array<Array<String>>

        private lateinit var nfca: NfcA
        private var currentSector: Byte = 0
        private val sramSize = 64

        private var identifier: ByteArray = ByteArray(7){0x00}
        private var voltage: Double = 0.0
        private var temperature: Double = 0.0
        private var buttom: Byte = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.d(TAG, "onCreate")

        findViewById<TextView>(R.id.status).text = "Waiting..."

        mAdapter = NfcAdapter.getDefaultAdapter(this)
        if (!mAdapter.isEnabled) {
            Toast.makeText(this, "Please enable NFC.", Toast.LENGTH_SHORT).show()
        }

        mPendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_MUTABLE
        )

        val ndef = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED)
        try {
            ndef.addDataType("*/*")
        } catch (e: MalformedMimeTypeException) {
            throw RuntimeException("fail", e)
        }
        mFilters = arrayOf(ndef)
        mTechLists = arrayOf(arrayOf<String>(NfcA::class.java.name))

        Log.d(TAG, intent.toString() + intent.getParcelableExtra(NfcAdapter.EXTRA_TAG))
        if (intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_TECH_DISCOVERED
        ) {
            scanNTAG(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG,"onResume")

        mAdapter?.enableForegroundDispatch(this, mPendingIntent, mFilters, mTechLists)
    }

    @WorkerThread
    private suspend fun preBackground() {
        Log.d(TAG, "preBackground")
        withContext(Dispatchers.IO) {
            if (identifier[0] != 0x04.toByte()) throw IOException("Not a NXP tag.")
            if (!(getVersion() contentEquals byteArrayOf(
                    0x00.toByte(),
                    0x04.toByte(),
                    0x04.toByte(),
                    0x05.toByte(),
                    0x02.toByte(),
                    0x02.toByte(),
                    0x15.toByte(),
                    0x03.toByte()
                ))) throw IOException("Not a NTAG I2C plus 2k.")
            val timeOutStart = System.currentTimeMillis()
            while (true) {
                if ((System.currentTimeMillis() - timeOutStart) > 5000) throw IOException("Timeout.")
                sectorSelect(0)
                val data = read(0xEC.toByte())?: continue
                if (data[0] and 0x40.toByte() == 0x00.toByte()) continue
                if (data[0] and 0x01.toByte() == 0x00.toByte()) continue
                if (data[6] and 0x20.toByte() == 0x00.toByte()) continue
                break
            }
        }
    }

    @WorkerThread
    private suspend fun processBackground(): ByteArray {
        Log.d(TAG, "processBackground")
        return withContext(Dispatchers.IO) {
            Thread.sleep(110)

            val data = ByteArray(sramSize){0x00}
            data[sramSize-9] = 'E'.toByte() // Enabled temperature sensor
            data[sramSize-4] = 'L'.toByte() // LED_Demo()
            data[sramSize-3] = '0'.toByte() // LED OFF
            sectorSelect(0)
            fastWrite(data, 0xF0.toByte(), 0xFF.toByte())

            Thread.sleep(110)

            sectorSelect(0)
            val response = fastRead(0xF0.toByte(), 0xFF.toByte())?: throw IOException("Data loss.")
            if (sramSize > response.size) throw IOException("Data loss.")

            return@withContext response
        }
    }

    @UiThread
    private fun postBackground(response: ByteArray) {
        Log.d(TAG,"postBackground")

        voltage = response[sramSize-7].toDouble() * 256 + response[sramSize-8].toDouble()
        if (voltage != 0.0) voltage = (0x3FF * 2.048) / voltage
        if (voltage > 5.0) voltage = 5.0
        if (voltage < 0.0) voltage = 0.0

        findViewById<TextView>(R.id.voltage).text = "%6.2f V".format(voltage)

        temperature = (response[sramSize-6].toDouble() * 256 + response[sramSize-5].toDouble()) / 256
        if (temperature > 127) temperature = 128 - temperature

        findViewById<TextView>(R.id.temperature).text = "%6.1f ℃".format(temperature)

        buttom = response[sramSize-2] and 0x07

        if ((buttom and 0x01) == 0x01.toByte()) {
            findViewById<Button>(R.id.bRED).text = "ON"
        } else {
            findViewById<Button>(R.id.bRED).text = "OFF"
        }
        if ((buttom and 0x02) == 0x02.toByte()) {
            findViewById<Button>(R.id.bBLUE).text = "ON"
        } else {
            findViewById<Button>(R.id.bBLUE).text = "OFF"
        }
        if ((buttom and 0x04) == 0x04.toByte()) {
            findViewById<Button>(R.id.bGREEN).text = "ON"
        } else {
            findViewById<Button>(R.id.bGREEN).text = "OFF"
        }

        findViewById<TextView>(R.id.debug).text = byteToHex(response)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent : $intent" + intent.getParcelableExtra(NfcAdapter.EXTRA_TAG))

        scanNTAG(intent)
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG,"onPause")

        mAdapter?.disableForegroundDispatch(this)
    }

    private fun scanNTAG(intent: Intent) {
        Log.d(TAG,"scanNTAG")

        lifecycleScope.launch {
            val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            if (tag != null) identifier = tag.id
            nfca = NfcA.get(tag)
            findViewById<TextView>(R.id.status).text = "Scanning... " + byteToHex(identifier)

            try {
                connect()
                preBackground()
                while(true) {
                    val response = processBackground()
                    postBackground(response)
                }
            }
            catch (e: IOException) {
                e.printStackTrace()
                //close()
            }

            findViewById<TextView>(R.id.status).text = "Waiting..."
            findViewById<TextView>(R.id.voltage).text = "  -.-- V".format(voltage)
            findViewById<TextView>(R.id.temperature).text = "  --.- ℃".format(temperature)
            findViewById<Button>(R.id.bRED).text = "OFF"
            findViewById<Button>(R.id.bBLUE).text = "OFF"
            findViewById<Button>(R.id.bGREEN).text = "OFF"
        }
    }

    @Throws(IOException::class)
    fun connect() {
        nfca.connect()
        nfca.timeout = 20
        currentSector = 0
    }

    @Throws(IOException::class)
    fun close() {
        nfca.close()
        currentSector = 0
    }

    @Throws(IOException::class)
    fun getVersion(): ByteArray? {
        return nfca.transceive(
            byteArrayOf(
                0x60.toByte()
            )
        )
    }

    @Throws(IOException::class)
    fun sectorSelect(s: Byte) {
        if (currentSector == s) {
            return
        }
        nfca.transceive(
            byteArrayOf(
                0xC2.toByte(),
                0xFF.toByte()
            )
        )
        try { // Passive ACK
            nfca.transceive(
                byteArrayOf(
                    0x00.toByte(),
                    0x00.toByte(),
                    0x00.toByte(),
                    0x00.toByte()
                )
            )
        } catch (e: IOException) {
            e.printStackTrace()
        }
        currentSector = s
    }

    @Throws(IOException::class)
    fun read(blk: Byte): ByteArray? {
        return nfca.transceive(
            byteArrayOf(
                0x30.toByte(),
                blk
            )
        )
    }

    @Throws(IOException::class)
    fun fastWrite(d: ByteArray, s: Byte, e: Byte){
        nfca.timeout = 500
        nfca.transceive(
            byteArrayOf(
                0xA6.toByte(),
                s,
                e
            ) + d
        )
        nfca.timeout = 20
    }

    @Throws(IOException::class)
    fun fastRead(s: Byte, e: Byte): ByteArray? {
        nfca.timeout = 500
        val d = nfca.transceive(
            byteArrayOf(
                0x3A.toByte(),
                s,
                e
            )
        )
        nfca.timeout = 20
        return d
    }

    private fun byteToHex(b: ByteArray): String {
        var s = ""
        for (e in b){
            s += "%02X ".format(e)
        }
        return s
    }
}