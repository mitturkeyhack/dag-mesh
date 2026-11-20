package com.dagmesh.app.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dagmesh.app.App
import com.dagmesh.app.enkaz.Bulucu
import com.dagmesh.app.enkaz.EnkazMode
import com.dagmesh.app.map.PinStore
import com.dagmesh.app.map.PinType
import com.dagmesh.app.media.LocationHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val Orange = Color(0xFFF2935A)
private val OrangeInk = Color(0xFF2B1406)
private val Teal = Color(0xFF5FD3BE)
private val TealInk = Color(0xFF062A24)
private val Sub = Color(0xFFA3ADC2)
private val CardBg = Color(0xFF19223A)
private val Card2 = Color(0xFF222C46)
private val WarnBg = Color(0xFF1F1812)
private val WarnBorder = Color(0xFF4A3A2A)
private val WarnText = Color(0xFFF1C9A9)

@Composable
private fun rememberNow(stepMs: Long = 500): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (isActive) { now = System.currentTimeMillis(); delay(stepMs) } }
    return now
}

private fun hasMic(context: android.content.Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

// ---------------------------------------------------------------------------------------------
// Enkazdayım
// ---------------------------------------------------------------------------------------------

@Composable
fun EnkazScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val st by EnkazMode.state.collectAsStateWithLifecycle()
    val sosOut by App.instance.mesh.sos.outgoing.collectAsStateWithLifecycle()
    val now = rememberNow()
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) EnkazMode.retryMic(context)
    }

    // Ekrana gelince mod kapalıysa aç (Afet Merkezi'nden "Enkazdayım").
    LaunchedEffect(Unit) {
        if (!EnkazMode.state.value.active) {
            EnkazMode.start(context, auto = false)
            if (!hasMic(context)) micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Pil için: 20 sn dokunulmazsa ekranı en kısığa al.
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val activity = context as? Activity
    LaunchedEffect(lastTouch) {
        setBrightness(activity, -1f)
        delay(20_000)
        setBrightness(activity, 0.02f)
    }
    DisposableEffect(Unit) { onDispose { setBrightness(activity, -1f) } }

    val pulse = rememberInfiniteTransition(label = "enkaz")
    val ring by pulse.animateFloat(1f, 1.25f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "ring")

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF070A12))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    lastTouch = System.currentTimeMillis()
                }
            }
            .systemBarsPadding(),
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Geri", tint = Sub) }
                Spacer(Modifier.weight(1f))
                Text("ENKAZ MODU AÇIK", color = Orange, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(48.dp))
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(200.dp).scale(ring).clip(CircleShape).background(Orange.copy(alpha = 0.10f)))
                Box(Modifier.size(160.dp).clip(CircleShape).background(Orange.copy(alpha = 0.18f)))
                Box(Modifier.size(124.dp).clip(CircleShape).background(Orange), contentAlignment = Alignment.Center) {
                    Icon(SeismoIcon, contentDescription = null, tint = OrangeInk, modifier = Modifier.size(64.dp))
                }
            }
            val heardRecently = st.heardAt > 0 && now - st.heardAt < 20_000
            Text(
                when {
                    heardRecently -> "Bir ses duydum, cevap veriyorum"
                    st.mic == EnkazMode.Mic.LISTENING -> "Seni duymaya çalışıyorum"
                    else -> "Düzenli aralıkla ötüyorum"
                },
                color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            val next = ((st.nextBeaconAt - now) / 1000).coerceAtLeast(0)
            Text(
                (if (st.mic == EnkazMode.Mic.LISTENING) "Biri seslenince ya da vurunca hemen öterim. " else "") +
                    "Sonraki düdük $next sn sonra.",
                color = Color(0xFFC7CEDC), fontSize = 15.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Spacer(Modifier.height(20.dp))
            Surface(color = Color(0xFF121827), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    InfoRow("Pil", when {
                        st.battery < 0 -> "ölçülüyor"
                        st.battery <= 30 -> "%${st.battery} · daha seyrek ötüyorum"
                        else -> "%${st.battery}"
                    }, if (st.battery in 0..30) Color(0xFFF4BE4F) else Color.White)
                    InfoRow("Titreşim vuruşu", "açık", Teal)
                    when (st.mic) {
                        EnkazMode.Mic.LISTENING -> InfoRow("Dinleme", "açık", Teal)
                        EnkazMode.Mic.NO_PERMISSION -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Dinleme", color = Sub, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Mikrofon izni ver", color = Orange) }
                        }
                        else -> InfoRow("Dinleme", "kapalı · sadece öterim", Color(0xFFF4BE4F))
                    }
                    val out = sosOut
                    InfoRow(
                        "Mesh SOS",
                        when {
                            out == null -> "kapalı"
                            out.helpers.isNotEmpty() -> "${out.helpers.size} kişi geliyor"
                            out.seenBy.isNotEmpty() -> "${out.seenBy.size} telefon aldı"
                            else -> "gönderiliyor"
                        },
                        if (out != null && (out.helpers.isNotEmpty() || out.seenBy.isNotEmpty())) Teal else Color.White,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Surface(color = WarnBg, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.5.dp, WarnBorder), modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Gücün varsa boruya ya da duvara 3 kez vur. Sesini boşa harcama; sesler duyunca bağır. Toz varsa ağzını kapat.",
                    color = WarnText, fontSize = 14.sp, modifier = Modifier.padding(14.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    onClick = { lastTouch = System.currentTimeMillis(); EnkazMode.beaconNow() },
                    color = Orange, shape = RoundedCornerShape(50), modifier = Modifier.weight(1f).height(60.dp),
                ) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(SeismoIcon, contentDescription = null, tint = OrangeInk, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Şimdi öt", color = OrangeInk, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
                HoldToStop(Modifier.weight(1f).height(60.dp)) {
                    EnkazMode.stop(context)
                    onBack()
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Ekran 20 sn sonra kararır · telefonu kilitlesen de çalışır", color = Color(0xFF6E7891), fontSize = 12.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun setBrightness(activity: Activity?, value: Float) {
    val w = activity?.window ?: return
    val lp = w.attributes
    lp.screenBrightness = if (value < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else value
    w.attributes = lp
}

@Composable
private fun InfoRow(key: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth()) {
        Text(key, color = Sub, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** Yanlışlıkla kapanmasın: 2 sn basılı tutunca kapanır. */
@Composable
private fun HoldToStop(modifier: Modifier, onStop: () -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF121827))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    val start = System.currentTimeMillis()
                    var done = false
                    while (true) {
                        val up = withTimeoutOrNull(16) { waitForUpOrCancellation(); true }
                        progress = ((System.currentTimeMillis() - start) / 2000f).coerceAtMost(1f)
                        if (!done && progress >= 1f) { done = true; onStop() }
                        if (up == true) break
                    }
                    progress = 0f
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress).align(Alignment.CenterStart).background(Color(0xFF3A1E1E)))
        Text(
            if (progress > 0f) "Bırakma…" else "Kapatmak için\nbasılı tut",
            color = Color(0xFFC7CEDC), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Enkazda ara (bulucu)
// ---------------------------------------------------------------------------------------------

@Composable
fun BulucuScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val st by Bulucu.state.collectAsStateWithLifecycle()
    val sosIn by App.instance.mesh.sos.incoming.collectAsStateWithLifecycle()
    val now = rememberNow()
    val scope = rememberCoroutineScope()
    var micDenied by remember { mutableStateOf(false) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) Bulucu.start(context) else micDenied = true
    }

    DisposableEffect(Unit) {
        if (hasMic(context)) Bulucu.start(context) else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        // Dinlerken ekran kapanmasın.
        (context as? Activity)?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            (context as? Activity)?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Bulucu.stop(context)
        }
    }

    val last = st.last
    val found = last != null && now - last.at < 120_000

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Geri") }
            Text("Enkazda ara", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        if (micDenied || (!st.active && !hasMic(context))) {
            Surface(color = WarnBg, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.5.dp, WarnBorder), modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Mikrofon izni gerekli", color = WarnText, fontWeight = FontWeight.Bold)
                    Text("Düdüğü duyabilmek için Dağ Mesh'e mikrofon izni ver.", color = WarnText, fontSize = 14.sp)
                    TextButton(onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("İzin ver", color = Orange) }
                }
            }
            return@Column
        }

        if (found && last != null) {
            Surface(color = Color(0xFF2A1C12), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.5.dp, Orange), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Orange), contentAlignment = Alignment.Center) {
                        Icon(SeismoIcon, contentDescription = null, tint = OrangeInk, modifier = Modifier.size(24.dp))
                    }
                    Column {
                        Text("Dağ Mesh düdüğü duyuldu!", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        val ago = ((now - last.at) / 1000).coerceAtLeast(0)
                        Text((if (last.vibration) "Titreşim de alındı · " else "") + "$ago sn önce", fontSize = 13.sp, color = WarnText)
                    }
                }
            }
        } else {
            Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StepChip(1, "Telefonu enkaza yasla", Modifier.weight(1f))
                StepChip(2, "Herkes sussun", Modifier.weight(1f))
                StepChip(3, "Seslen, bekle", Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(18.dp))
        Meter(
            percent = if (found && last != null) last.percent else 0,
            color = if (found) Orange else Color(0xFF8E98AE),
            big = when {
                !found || last == null -> "Dinleniyor"
                last.percent >= 60 -> "SICAK"
                last.percent >= 30 -> "ILIK"
                else -> "SOĞUK"
            },
            small = if (!found || last == null) "Dağ Mesh düdüğü aranıyor" else "Sinyal %${last.percent} · " + when (last.trend) {
                Bulucu.Trend.UP -> "güçleniyor ↑"
                Bulucu.Trend.DOWN -> "zayıflıyor ↓"
                Bulucu.Trend.SAME -> "aynı"
                Bulucu.Trend.FIRST -> "ilk ölçüm"
            },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        LevelBars(st.levels, if (found) Orange else Teal, Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(46.dp))
        Text(
            if (st.micSilent) "Mikrofon ses almıyor · ekranı açık tut" else "Son 20 saniye",
            fontSize = 12.sp, color = if (st.micSilent) Color(0xFFF4BE4F) else Color(0xFF6E7891),
            modifier = Modifier.padding(start = 20.dp, top = 6.dp),
        )

        val calling = now < st.callingUntil
        val listening = !calling && now < st.listeningUntil
        if (found && last != null) {
            Surface(color = CardBg, shape = RoundedCornerShape(18.dp), modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth()) {
                Text(
                    "Telefonu 1 m sağa, sola, ileri kaydır. Gösterge en yüksekken orası en yakın nokta. Kazmadan önce ekibe haber ver.",
                    fontSize = 14.sp, color = Color(0xFFC7CEDC), modifier = Modifier.padding(14.dp),
                )
            }
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    onClick = { scope.launch { markHere(context, last.percent, alert = false) } },
                    color = Orange, shape = RoundedCornerShape(50), modifier = Modifier.weight(1f).height(56.dp),
                ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Buraya işaret koy", color = OrangeInk, fontWeight = FontWeight.ExtraBold) } }
                Surface(
                    onClick = { scope.launch { markHere(context, last.percent, alert = true) } },
                    color = Color.Transparent, border = BorderStroke(1.5.dp, Teal), shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(1f).height(56.dp),
                ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Herkese haber ver", color = Teal, fontWeight = FontWeight.Bold) } }
            }
            Text(
                "İşaret haritaya düşer ve mesh ile yakındaki herkese gider.",
                fontSize = 12.sp, color = Sub, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).fillMaxWidth(),
            )
            TextButton(
                onClick = { Bulucu.call(context) },
                enabled = !calling,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text(if (calling) "Söylüyor…" else "Tekrar seslen", color = Teal) }
        } else {
            Surface(
                onClick = { Bulucu.call(context) },
                enabled = st.active && !calling,
                color = Teal, shape = RoundedCornerShape(50),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp).fillMaxWidth().height(58.dp),
            ) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Campaign, contentDescription = null, tint = TealInk)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when {
                            calling -> "Söylüyor…"
                            listening -> "Dinleniyor… ${((st.listeningUntil - now) / 1000).coerceAtLeast(0)} sn"
                            else -> "\"Sesimi duyan var mı?\" de"
                        },
                        color = TealInk, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
            Text(
                "Telefon bu sözü yüksek sesle söyler, sonra susup dinler. Enkazdaki Dağ Mesh hemen cevap verir.",
                fontSize = 12.sp, color = Sub, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).fillMaxWidth(),
            )
        }

        val sosList = sosIn.values.sortedByDescending { it.lastSeen }.take(3)
        if (sosList.isNotEmpty()) {
            Text("YAKINDAKİ SOS (MESH)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = Sub, modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp))
            sosList.forEach { s ->
                Surface(color = Color(0xFF2A1416), shape = RoundedCornerShape(20.dp), border = BorderStroke(1.5.dp, SosRed), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(SosRed), contentAlignment = Alignment.Center) {
                            Text("SOS", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                        }
                        Column {
                            Text(s.name, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(agoText(s.lastSeen, now) + if (s.point != null) " · konumu var" else "", fontSize = 13.sp, color = Color(0xFFF1B5B7))
                        }
                    }
                }
            }
        }
    }
}

private suspend fun markHere(context: android.content.Context, percent: Int, alert: Boolean) {
    val loc = LocationHelper.current(context, timeoutMs = 12_000) ?: LocationHelper.lastKnownQuick(context)
    if (loc == null) {
        Toast.makeText(context, "Konum bulunamadı · Konum açık mı?", Toast.LENGTH_LONG).show()
        return
    }
    PinStore.add(PinType.ENKAZ, loc.latitude, loc.longitude, "Dağ Mesh düdüğü duyuldu · sinyal %$percent", alert = alert)
    Toast.makeText(
        context,
        if (alert) "İşaret kondu, yakındaki herkese haber gitti" else "İşaret haritaya kondu",
        Toast.LENGTH_SHORT,
    ).show()
}

@Composable
private fun StepChip(n: Int, text: String, modifier: Modifier) {
    Surface(color = CardBg, shape = RoundedCornerShape(14.dp), modifier = modifier.heightIn(min = 72.dp)) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(Card2), contentAlignment = Alignment.Center) {
                Text("$n", color = Teal, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            }
            Text(text, color = Color(0xFFC7CEDC), fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Meter(percent: Int, color: Color, big: String, small: String, modifier: Modifier = Modifier) {
    Box(modifier.size(240.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(14.dp)) {
            val stroke = 18.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(Card2, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (percent > 0) drawArc(color, 135f, 270f * percent / 100f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(big, color = color, fontSize = if (big.length > 6) 28.sp else 34.sp, fontWeight = FontWeight.ExtraBold)
            Text(small, color = Sub, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun LevelBars(levels: List<Float>, color: Color, modifier: Modifier) {
    val values = List(20 - levels.size.coerceAtMost(20)) { 0.03f } + levels.takeLast(20)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        values.forEach { v ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(v.coerceIn(0.06f, 1f))
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (v > 0.25f) color else Color(0xFF2E3650))
            )
        }
    }
}
