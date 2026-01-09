package com.example.stappenteller
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import kotlinx.coroutines.CoroutineScope
import android.Manifest
import androidx.compose.ui.text.style.TextAlign


// DE KLEUREN

val BackgroundDark = Color(0xFF102A43)

val CardDark = Color(0xFF1F3A53)

val AccentBlue = Color(0xFF2196F3)

val AccentGreen = Color(0xFF4CAF50)

val TextWhite = Color.White

val TextGray = Color.LightGray


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Permissies vragen (Nu ook voor notificaties ivm de Service)
        val permissies = mutableListOf(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissies.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val launcher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Als we permissie hebben, starten we de service!
            startOnzeService()
        }
        launcher.launch(permissies.toTypedArray())

        // Voor de zekerheid ook starten als we al permissie hadden
        startOnzeService()

        setContent {
            var huidigScherm by remember { mutableStateOf("home") }

            when (huidigScherm) {
                "home" -> StappentellerHome(
                    gaNaarRapport = { huidigScherm = "rapport" },
                    gaNaarSettings = { huidigScherm = "settings" }
                )
                "rapport" -> RapportScherm(gaTerug = { huidigScherm = "home" })
                "settings" -> InstellingenScherm(gaTerug = { huidigScherm = "home" })
            }
        }
    }

    private fun startOnzeService() {
        val serviceIntent = Intent(this, StappenService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}

// --- SCHERM 1: HOME (MET SERVICE ONDERSTEUNING) ---
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StappentellerHome(gaNaarRapport: () -> Unit, gaNaarSettings: () -> Unit) {
    val context = LocalContext.current
    val sharedPreferences = remember { context.getSharedPreferences("StappenData", Context.MODE_PRIVATE) }
    val vandaagDatum = remember { LocalDate.now().toString() }

    // STATES
    // We lezen de startwaarde direct uit het geheugen
    var stappenVandaag by remember { mutableStateOf(sharedPreferences.getInt("stappen_$vandaagDatum", 0)) }
    var dagDoel by remember { mutableStateOf(sharedPreferences.getInt("dag_doel", 10000)) }

    val gewicht = remember { sharedPreferences.getFloat("gewicht", 75f) }
    val stapLengte = remember { sharedPreferences.getFloat("stap_lengte", 75f) }

    var toonDoelDialoog by remember { mutableStateOf(false) }
    var toonDebugInfo by remember { mutableStateOf(false) }
    var debugSensorWaarde by remember { mutableStateOf(0) }
    var debugVorigeWaarde by remember { mutableStateOf(0) }

    // --- DEEL 1: LUISTEREN NAAR DE SERVICE (BROADCAST RECEIVER) ---
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // De Service roept! Er zijn nieuwe stappen!
                val nieuweStappen = intent?.getIntExtra("nieuwe_stappen", 0) ?: 0
                val sensorRuweWaarde = intent?.getIntExtra("debug_sensor", 0) ?: 0
                val sensorVorigeWaarde = intent?.getIntExtra("debug_vorige", 0) ?: 0

                if (nieuweStappen > 0) {
                    stappenVandaag = nieuweStappen
                }

                // Update de debug states
                if (sensorRuweWaarde > 0) {
                    debugSensorWaarde = sensorRuweWaarde
                    debugVorigeWaarde = sensorVorigeWaarde
                }
            }
        }

        // We zetten de 'oren' van de UI open
        val filter = IntentFilter("com.example.stappenteller.UPDATE_STAPPEN")
        // Voor nieuwere Androids is RECEIVER_NOT_EXPORTED verplicht, voor oudere mag het niet.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    // --- DEEL 2: BEREKENINGEN ---
    val kilometers = (stappenVandaag * stapLengte) / 100000
    val calorieen = (stappenVandaag * gewicht * 0.0005).toInt()
    val wandelTijdMinuten = stappenVandaag / 100
    val tijdTekst = if (wandelTijdMinuten > 60) "${wandelTijdMinuten / 60}u ${wandelTijdMinuten % 60}m" else "${wandelTijdMinuten}m"
    val voortgang = (stappenVandaag.toFloat() / dagDoel.toFloat()).coerceIn(0f, 1f)

    // UI POPUP (Ongewijzigd)
    if (toonDoelDialoog) {
        var nieuweInvoer by remember { mutableStateOf(dagDoel.toString()) }
        AlertDialog(
            onDismissRequest = { toonDoelDialoog = false },
            title = { Text(stringResource(R.string.goal_title)) },
            text = {
                OutlinedTextField(
                    value = nieuweInvoer,
                    onValueChange = { nieuweInvoer = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(R.string.goal_label)) }
                )
            },
            confirmButton = {
                Button(onClick = {
                    val nieuwDoel = nieuweInvoer.toIntOrNull()
                    if (nieuwDoel != null && nieuwDoel > 0) {
                        dagDoel = nieuwDoel
                        sharedPreferences.edit().putInt("dag_doel", nieuwDoel).apply()
                        toonDoelDialoog = false
                    }
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { toonDoelDialoog = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {},
                actions = {
                    IconButton(onClick = gaNaarSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = TextGray)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // DEBUG TEKST
            Text(
                text = stringResource(R.string.today),
                style = MaterialTheme.typography.titleMedium,
                color = TextGray,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.combinedClickable(
                    onClick = { },
                    onLongClick = {
                        toonDebugInfo = !toonDebugInfo
                    }
                )
            )

            Spacer(modifier = Modifier.height(30.dp))

            // DE CIRKEL
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(280.dp).background(CardDark, CircleShape)
            ) {
                CircularProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxSize(), color = Color.Black.copy(alpha = 0.2f), strokeWidth = 20.dp, trackColor = Color.Transparent)
                CircularProgressIndicator(progress = { voortgang }, modifier = Modifier.fillMaxSize(), color = if (stappenVandaag >= dagDoel) Color(0xFFFFD700) else AccentGreen, strokeWidth = 20.dp, trackColor = Color.Transparent, strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$stappenVandaag", fontSize = 64.sp, fontWeight = FontWeight.ExtraBold, color = TextWhite)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { toonDoelDialoog = true }.padding(8.dp)) {
                        Text("/ $dagDoel", fontSize = 16.sp, color = TextGray)
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Filled.Edit, contentDescription = "Edit", tint = TextGray, modifier = Modifier.size(16.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // INFO KAARTEN
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                InfoKaartDonker(waarde = String.format("%.2f", kilometers), eenheid = "km", label = stringResource(R.string.distance))
                InfoKaartDonker(waarde = "$calorieen", eenheid = "kcal", label = stringResource(R.string.burned))
                InfoKaartDonker(waarde = tijdTekst, eenheid = "", label = stringResource(R.string.time))
            }
            Spacer(modifier = Modifier.height(50.dp))

            // KNOPPEN
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // RESET KNOP
                Button(
                    onClick = {
                        stappenVandaag = 0
                        sharedPreferences.edit().putInt("stappen_$vandaagDatum", 0).apply()
                        // Hier moeten we eigenlijk ook de Service vertellen dat hij moet resetten,
                        // maar voor nu is UI reset vaak genoeg omdat de Service gewoon doortelt
                        // en de volgende keer het verschil optelt bij 0.
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CardDark),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                ) { Text(stringResource(R.string.reset_button), color = Color(0xFFFF5252)) }

                Button(
                    onClick = gaNaarRapport,
                    colors = ButtonDefaults.buttonColors(containerColor = CardDark),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                ) {
                    Icon(Icons.Filled.List, contentDescription = "Report", tint = AccentBlue)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.report_button), color = AccentBlue)
                }
            }

            if (toonDebugInfo) {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "SERVICE MODE: Sensor: $debugSensorWaarde | Vorige: $debugVorigeWaarde",
                    color = Color.Yellow, // Geel is beter leesbaar voor debug
                    fontSize = 10.sp
                )
            }
        }
    }
}

// --- SCHERM 2: RAPPORT (VOLLEDIGE VERSIE MET GEMIDDELDE) ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RapportScherm(gaTerug: () -> Unit) {
    val context = LocalContext.current
    val sharedPreferences = remember { context.getSharedPreferences("StappenData", Context.MODE_PRIVATE) }

    val gewicht = remember { sharedPreferences.getFloat("gewicht", 75f) }
    // Let op: hier gebruiken we de berekende staplengte als die bestaat, anders 75cm
    val stapLengte = remember { sharedPreferences.getFloat("stap_lengte", 75f) }

    var geselecteerdeTab by remember { mutableStateOf("week") }
    var geselecteerdeMetric by remember { mutableStateOf("stappen") }
    var geselecteerdeIndex by remember { mutableStateOf<Int?>(null) }

    // DATA OPHALEN
    val weekData = remember {
        val lijst = mutableListOf<Pair<String, Int>>()
        val vandaag = LocalDate.now()
        val dagFormatter = DateTimeFormatter.ofPattern("EEE", java.util.Locale.getDefault())
        for (i in 6 downTo 0) {
            val datum = vandaag.minusDays(i.toLong())
            val stappen = sharedPreferences.getInt("stappen_$datum", 0)
            lijst.add(Pair(datum.format(dagFormatter), stappen))
        }
        lijst
    }

    val jaarData = remember {
        val lijst = mutableListOf<Pair<String, Int>>()
        val dezeMaand = YearMonth.now()
        for (i in 11 downTo 0) {
            val maand = dezeMaand.minusMonths(i.toLong())
            var maandTotaal = 0
            for (dag in 1..maand.lengthOfMonth()) {
                val datum = maand.atDay(dag).toString()
                val stappen = sharedPreferences.getInt("stappen_$datum", 0)
                maandTotaal += stappen
            }
            val maandNaam = maand.month.name.substring(0, 3).lowercase().replaceFirstChar { it.uppercase() }
            lijst.add(Pair(maandNaam, maandTotaal))
        }
        lijst
    }

    val huidigeData = if (geselecteerdeTab == "week") weekData else jaarData

    // GRAFIEK BEREKENINGEN
    val grafiekData = huidigeData.map { (label, stappen) ->
        val waarde = when (geselecteerdeMetric) {
            "kcal" -> (stappen * gewicht * 0.0005).toFloat()
            "afstand" -> (stappen * stapLengte) / 100000f
            "tijd" -> (stappen / 100f)
            else -> stappen.toFloat()
        }
        Pair(label, waarde)
    }

    val maxValue = grafiekData.maxOfOrNull { it.second } ?: 100f
    val schaal = maxOf(maxValue, 10f)

    val focusIndex = geselecteerdeIndex ?: (huidigeData.size - 1)
    val focusLabel = grafiekData.getOrNull(focusIndex)?.first ?: ""
    val focusWaarde = grafiekData.getOrNull(focusIndex)?.second ?: 0f

    val eenheid = when(geselecteerdeMetric) {
        "kcal" -> "Kcal"
        "afstand" -> "Km"
        "tijd" -> "Min"
        else -> stringResource(R.string.steps_label)
    }

    val titelTekst = when(geselecteerdeTab) {
        "week" -> {
            // SLIMME LOGICA: Alleen 'Stappen' tonen als we ook echt naar stappen kijken.
            // Anders tonen we het generieke 'Overzicht'.
            if (geselecteerdeMetric == "stappen") {
                stringResource(R.string.steps_this_week)
            } else {
                stringResource(R.string.overview_week)
            }
        }
        "maand" -> stringResource(R.string.overview_month)
        else -> stringResource(R.string.overview_year)
    }

    val totaalTekst = when(geselecteerdeTab) {
        "week" -> stringResource(R.string.total_this_week)
        "maand" -> stringResource(R.string.total_month)
        else -> stringResource(R.string.total_year)
    }

    // TOTALEN BEREKENEN
    val totaalStappen = huidigeData.sumOf { it.second }
    val totaalKm = (totaalStappen * stapLengte) / 100000
    val totaalKcal = (totaalStappen * gewicht * 0.0005).toInt()
    val totaalMinuten = totaalStappen / 100
    val totaalTijdTekst = if (totaalMinuten > 60) "${totaalMinuten / 60}u ${totaalMinuten % 60}m" else "${totaalMinuten}m"

    // NIEUW: GEMIDDELDE BEREKENING
    val aantalDagenInPeriode = when(geselecteerdeTab) {
        "week" -> 7
        "maand" -> 30
        "jaar" -> 365
        else -> 1
    }
    // Voorkom delen door 0
    val gemiddeldPerDag = if (aantalDagenInPeriode > 0) totaalStappen / aantalDagenInPeriode else 0

    BackHandler { gaTerug() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.report_button)) },
                navigationIcon = { IconButton(onClick = gaTerug) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark, titleContentColor = TextWhite, navigationIconContentColor = TextWhite)
            )
        },
        containerColor = BackgroundDark
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // 1. TABS (Week / Maand / Jaar)
            Row(modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp), horizontalArrangement = Arrangement.Center) {
                TabKnop(tekst = stringResource(R.string.tab_week), actief = geselecteerdeTab == "week") { geselecteerdeTab = "week"; geselecteerdeIndex = null }
                Spacer(modifier = Modifier.width(8.dp))
                TabKnop(tekst = stringResource(R.string.tab_month), actief = geselecteerdeTab == "maand") { geselecteerdeTab = "maand"; geselecteerdeIndex = null }
                Spacer(modifier = Modifier.width(8.dp))
                TabKnop(tekst = stringResource(R.string.tab_year), actief = geselecteerdeTab == "jaar") { geselecteerdeTab = "jaar"; geselecteerdeIndex = null }
            }

            // 2. FOCUS KAART (Geselecteerde waarde)
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp)
            ) {
                Column(modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "$titelTekst ($focusLabel)", color = TextGray, fontSize = 14.sp)
                    Text(
                        text = if (geselecteerdeMetric == "tijd") "${focusWaarde.toInt()} min" else String.format("%.1f $eenheid", focusWaarde),
                        color = TextWhite,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold
                    )
                    val labelText = if (geselecteerdeIndex == null) stringResource(R.string.average_label) else stringResource(R.string.selected_label)
                    Text(text = labelText, color = AccentGreen, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 3. DE GRAFIEK (Balkjes)
            Row(modifier = Modifier
                .fillMaxWidth()
                .height(200.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
                grafiekData.forEachIndexed { index, (label, waarde) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier
                        .weight(1f)
                        .clickable { geselecteerdeIndex = index }) {
                        val isGeselecteerd = (index == geselecteerdeIndex)
                        val balkKleur = if (isGeselecteerd) Color(0xFF81C784) else AccentGreen
                        val hoogtePercentage = (waarde / schaal) * 0.8f

                        if (geselecteerdeTab == "week" && isGeselecteerd) {
                            Text(text = "${waarde.toInt()}", color = TextWhite, fontSize = 10.sp, maxLines = 1)
                        }

                        Box(modifier = Modifier
                            .fillMaxWidth(if (geselecteerdeTab == "week") 0.6f else 0.8f)
                            .fillMaxHeight(hoogtePercentage.coerceAtLeast(0.01f))
                            .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                            .background(balkKleur))

                        val toonLabel = when(geselecteerdeTab) {
                            "week", "jaar" -> true
                            "maand" -> (index + 1) % 5 == 0 || index == 0
                            else -> true
                        }

                        if (toonLabel) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = label.take(3), color = if(isGeselecteerd) TextWhite else TextGray, fontSize = 8.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // 4. METRIC KNOPPEN (Stappen, Kcal, Tijd, Afstand)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricKnop(stringResource(R.string.metric_steps), geselecteerdeMetric == "stappen") { geselecteerdeMetric = "stappen" }
                MetricKnop(stringResource(R.string.metric_kcal), geselecteerdeMetric == "kcal") { geselecteerdeMetric = "kcal" }
                MetricKnop(stringResource(R.string.metric_time), geselecteerdeMetric == "tijd") { geselecteerdeMetric = "tijd" }
                MetricKnop(stringResource(R.string.metric_dist), geselecteerdeMetric == "afstand") { geselecteerdeMetric = "afstand" }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 5. TOTAAL KAART + GEMIDDELDE
            Card(colors = CardDefaults.cardColors(containerColor = CardDark)) {
                Column(modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()) {
                    Text(totaalTekst, color = TextGray)
                    Text("$totaalStappen ${stringResource(R.string.steps_label)}", color = TextWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold)

                    // HIER IS DE NIEUWE REGEL:
                    Text(
                        // Hier zeggen we: Haal de tekst op en vul het getal 'gemiddeldPerDag' in op de plek van %d
                        text = stringResource(R.string.average_per_day_format, gemiddeldPerDag),
                        color = AccentGreen,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        InfoKaartDonker(String.format("%.1f", totaalKm), "km", "")
                        InfoKaartDonker("$totaalKcal", "kcal", "")
                        InfoKaartDonker(totaalTijdTekst, "", "")
                    }
                }
            }
        }
    }
}

// --- SCHERM 3: INSTELLINGEN ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstellingenScherm(gaTerug: () -> Unit) {
    val context = LocalContext.current
    val sharedPreferences = remember { context.getSharedPreferences("StappenData", Context.MODE_PRIVATE) }

    var gewichtInput by remember { mutableStateOf(sharedPreferences.getFloat("gewicht", 75f).toString()) }
    var lengteInput by remember { mutableStateOf(sharedPreferences.getInt("lichaams_lengte", 175).toString()) }

    val savedMessage = stringResource(R.string.saved_message)

    BackHandler { gaTerug() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { IconButton(onClick = gaTerug) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark, titleContentColor = TextWhite, navigationIconContentColor = TextWhite)
            )
        },
        containerColor = BackgroundDark
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.Start
        ) {

            // 1. GEWICHT INPUT
            Text(stringResource(R.string.weight_label), color = TextGray, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = gewichtInput,
                onValueChange = { gewichtInput = it },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextWhite,
                    unfocusedTextColor = TextWhite,
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = TextGray
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 2. LENGTE INPUT
            Text(stringResource(R.string.height_label), color = TextGray, fontSize = 14.sp)
            Text(stringResource(R.string.height_hint), color = Color.Gray, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = lengteInput,
                onValueChange = { lengteInput = it },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextWhite,
                    unfocusedTextColor = TextWhite,
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = TextGray
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(40.dp))

            // 3. OPSLAAN KNOP
            Button(
                onClick = {
                    val gewicht = gewichtInput.toFloatOrNull()
                    val lichaamsLengte = lengteInput.toIntOrNull()

                    if (gewicht != null && lichaamsLengte != null) {
                        val berekendeStapLengte = lichaamsLengte * 0.414f

                        sharedPreferences.edit()
                            .putFloat("gewicht", gewicht)
                            .putInt("lichaams_lengte", lichaamsLengte)
                            .putFloat("stap_lengte", berekendeStapLengte)
                            .apply()

                        Toast.makeText(context, savedMessage, Toast.LENGTH_SHORT).show()
                        gaTerug()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
            ) {
                Text(stringResource(R.string.save), fontSize = 18.sp)
            }

            // 4. DE HANDTEKENING (HIER IS HIJ WEER!)
            Spacer(modifier = Modifier.weight(1f)) // Duwt de tekst naar beneden

            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.about_text),
                    color = Color.Gray,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

// --- HULP FUNCTIES ---


@Composable

fun MetricKnop(tekst: String, actief: Boolean, onClick: () -> Unit) {

    Button(

        onClick = onClick,

        colors = ButtonDefaults.buttonColors(containerColor = if (actief) AccentBlue else CardDark, contentColor = TextWhite),

        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),

        modifier = Modifier.height(36.dp)

    ) { Text(tekst, fontSize = 10.sp) }

}


@Composable

fun TabKnop(tekst: String, actief: Boolean, onClick: () -> Unit) {

    Button(

        onClick = onClick,

        colors = ButtonDefaults.buttonColors(containerColor = if (actief) AccentGreen else Color.Transparent, contentColor = if (actief) TextWhite else TextGray),

        border = if (!actief) androidx.compose.foundation.BorderStroke(1.dp, TextGray) else null,

        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),

        modifier = Modifier.height(36.dp)

    ) { Text(tekst, fontSize = 12.sp) }

}


private fun Nothing?.getString(goalReachedTitle: Int, doel: Unit): CharSequence {
    return TODO("Provide the return value")
}

annotation class dagDoel

@Composable

fun InfoKaartDonker(waarde: String, eenheid: String, label: String) {

    Column(horizontalAlignment = Alignment.CenterHorizontally) {

        Row(verticalAlignment = Alignment.Bottom) {

            Text(text = waarde, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextWhite)

            if (eenheid.isNotEmpty()) {

                Spacer(modifier = Modifier.width(2.dp))

                Text(text = eenheid, fontSize = 12.sp, color = TextGray, modifier = Modifier.padding(bottom = 3.dp))

            }

        }

        if (label.isNotEmpty()) Text(text = label, fontSize = 14.sp, color = TextGray)


    }
}

