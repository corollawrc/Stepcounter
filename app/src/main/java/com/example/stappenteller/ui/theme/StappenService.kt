package com.example.stappenteller

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.time.LocalDate

class StappenService : Service(), SensorEventListener {

    override fun onBind(intent: Intent?): IBinder? {
        return null // We binden niet, we starten alleen
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundService() // Meteen de melding tonen zodat we niet gekilld worden
        startStappenTellen()
    }

    private fun startForegroundService() {
        val channelId = "StappenServiceChannel"
        val channelName = "Stappenteller Service"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Stappenteller Actief")
            .setContentText("We tellen je stappen op de achtergrond")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Of je eigen voet-icoon!
            .setOngoing(true) // Gebruiker kan hem niet wegvegen (belangrijk!)
            .build()

        // ID 1001 is onze vaste Service ID
        // Bij Android 14 moeten we het type erbij vertellen
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1001, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(1001, notification)
        }
    }

    private fun startStappenTellen() {
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        stepSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_STEP_COUNTER) {
                verwerkStappen(it.values[0].toInt())
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Doen we niks mee
    }

    private fun verwerkStappen(huidigeSensorWaarde: Int) {
        val sharedPreferences = getSharedPreferences("StappenData", Context.MODE_PRIVATE)
        val vandaagDatum = LocalDate.now().toString()

        val vorigeSensorWaarde = sharedPreferences.getInt("vorige_sensor_waarde", -1)
        var stappenVandaag = sharedPreferences.getInt("stappen_$vandaagDatum", 0)

        if (vorigeSensorWaarde != -1) {
            var verschil = huidigeSensorWaarde - vorigeSensorWaarde

            // De Reboot Check (onze Delta methode)
            if (verschil < 0) {
                verschil = huidigeSensorWaarde
            }

            if (verschil > 0) {
                stappenVandaag += verschil

                sharedPreferences.edit()
                    .putInt("stappen_$vandaagDatum", stappenVandaag)
                    .putInt("vorige_sensor_waarde", huidigeSensorWaarde)
                    .apply()

                // HIER IS DE AANPASSING:
                val intent = Intent("com.example.stappenteller.UPDATE_STAPPEN")
                intent.putExtra("nieuwe_stappen", stappenVandaag)

                // NIEUW: We sturen de ruwe data mee voor de debug mode!
                intent.putExtra("debug_sensor", huidigeSensorWaarde)
                intent.putExtra("debug_vorige", vorigeSensorWaarde)

                sendBroadcast(intent)
            }
        } else {
            // Eerste keer ooit
            sharedPreferences.edit().putInt("vorige_sensor_waarde", huidigeSensorWaarde).apply()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Als de service stopt (zou niet mogen), stoppen we met luisteren
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager.unregisterListener(this)
    }
}