package ir.appointment.voice.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.appointment.voice.data.AppointmentEntity
import ir.appointment.voice.ui.theme.AccentTeal
import ir.appointment.voice.ui.theme.DangerRed
import ir.appointment.voice.ui.theme.SurfaceWhite
import ir.appointment.voice.ui.theme.TextPrimary
import ir.appointment.voice.ui.theme.TextSecondary
import ir.appointment.voice.ui.theme.VividPurple
import ir.appointment.voice.viewmodel.AppointmentViewModel
import kotlinx.coroutines.launch
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AppointmentListScreen(
    viewModel: AppointmentViewModel,
    onBack: () -> Unit
) {
    val appointments by viewModel.appointments.collectAsState()
    val playingId by viewModel.playingId.collectAsState()
    val editingAppointment by viewModel.editingAppointment.collectAsState()
    val userMessage by viewModel.userMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) } // 0 = آینده, 1 = گذشته

    LaunchedEffect(userMessage) {
        val msg = userMessage ?: return@LaunchedEffect
        val job = launch { snackbarHostState.showSnackbar(msg.text) }
        kotlinx.coroutines.delay(msg.durationMillis)
        snackbarHostState.currentSnackbarData?.dismiss()
        job.join()
        viewModel.consumeUserMessage()
    }

    fun handleDelete(appointment: AppointmentEntity) {
        viewModel.requestDelete(appointment)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "قرار ملاقات حذف شد",
                actionLabel = "واگرد",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoDelete(appointment)
            }
        }
    }

    val (upcoming, past) = remember(appointments) {
        val now = System.currentTimeMillis()
        val upcomingList = appointments.filter { it.sortTimestamp == null || it.sortTimestamp >= now }
        val pastList = appointments
            .filter { it.sortTimestamp != null && it.sortTimestamp < now }
            .sortedByDescending { it.sortTimestamp } // most recently-passed first
        upcomingList to pastList
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("قرار ملاقات‌ها", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "بازگشت")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = VividPurple,
                        titleContentColor = SurfaceWhite,
                        navigationIconContentColor = SurfaceWhite
                    )
                )
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = VividPurple,
                    contentColor = SurfaceWhite
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("آینده (${upcoming.size})") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("گذشته (${past.size})") }
                    )
                }
            }
        }
    ) { padding ->
        val visibleList = if (selectedTab == 0) upcoming else past

        if (visibleList.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (selectedTab == 0) "هنوز قرار ملاقاتی ثبت نشده است" else "قرار گذشته‌ای وجود ندارد",
                    color = TextSecondary,
                    fontSize = 16.sp
                )
            }
        } else if (selectedTab == 0) {
            val grouped = remember(upcoming) { groupByDateSection(upcoming) }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                grouped.forEach { (sectionTitle, rows) ->
                    stickyHeader {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background)
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(text = sectionTitle, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = VividPurple)
                        }
                    }
                    items(rows, key = { it.id }) { appointment ->
                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            AppointmentRow(
                                appointment = appointment,
                                isPlaying = playingId == appointment.id,
                                onPlayToggle = { viewModel.togglePlay(appointment) },
                                onEditRequest = { viewModel.startEdit(appointment) },
                                onDeleteRequest = { handleDelete(appointment) }
                            )
                        }
                    }
                }
            }
        } else {
            // Past tab: simple flat list, most recently-passed appointment first.
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) {
                items(past, key = { it.id }) { appointment ->
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        AppointmentRow(
                            appointment = appointment,
                            isPlaying = playingId == appointment.id,
                            onPlayToggle = { viewModel.togglePlay(appointment) },
                            onEditRequest = { viewModel.startEdit(appointment) },
                            onDeleteRequest = { handleDelete(appointment) },
                            faded = true
                        )
                    }
                }
            }
        }
    }

    editingAppointment?.let { appointment ->
        AppointmentEditDialog(
            appointment = appointment,
            onSave = { updated -> viewModel.saveEdit(updated) },
            onDismiss = { viewModel.cancelEdit() }
        )
    }
}

private fun groupByDateSection(list: List<AppointmentEntity>): List<Pair<String, List<AppointmentEntity>>> {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    val startOfToday = cal.timeInMillis
    val startOfTomorrow = startOfToday + 24L * 3600 * 1000
    val startOfDayAfterTomorrow = startOfTomorrow + 24L * 3600 * 1000
    val startOfNextWeek = startOfToday + 7L * 24 * 3600 * 1000

    val groups = linkedMapOf<String, MutableList<AppointmentEntity>>()
    val order = listOf("امروز", "فردا", "این هفته", "به‌زودی", "تاریخ نامشخص")
    order.forEach { groups[it] = mutableListOf() }

    for (item in list) {
        val ts = item.sortTimestamp
        val key = when {
            ts == null -> "تاریخ نامشخص"
            ts < startOfTomorrow -> "امروز"
            ts < startOfDayAfterTomorrow -> "فردا"
            ts < startOfNextWeek -> "این هفته"
            else -> "به‌زودی"
        }
        groups[key]?.add(item)
    }

    return groups.filter { it.value.isNotEmpty() }.map { it.key to it.value }
}

@Composable
private fun AppointmentRow(
    appointment: AppointmentEntity,
    isPlaying: Boolean,
    onPlayToggle: () -> Unit,
    onEditRequest: () -> Unit,
    onDeleteRequest: () -> Unit,
    faded: Boolean = false
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (faded) SurfaceWhite.copy(alpha = 0.75f) else SurfaceWhite
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (faded) 1.dp else 3.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Play / Stop control
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(if (isPlaying) DangerRed else AccentTeal, CircleShape)
                    .clickable { onPlayToggle() },
                contentAlignment = Alignment.Center
            ) {
                if (isPlaying) {
                    Icon(Icons.Filled.Stop, contentDescription = "توقف پخش", tint = SurfaceWhite)
                } else {
                    PlayTriangle()
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextPrimary)) {
                            append(appointment.displayDate ?: "تاریخ نامشخص")
                        }
                        if (!appointment.weekdayName.isNullOrBlank()) {
                            withStyle(SpanStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, color = TextSecondary)) {
                                append("  (${appointment.weekdayName})")
                            }
                        }
                    }
                )

                if (!appointment.displayTime.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        color = VividPurple.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.wrapContentWidth()
                    ) {
                        Text(
                            text = appointment.displayTime,
                            color = VividPurple,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                if (!appointment.location.isNullOrBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.LocationOn, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(appointment.location, fontSize = 13.sp, color = TextSecondary)
                    }
                }
                if (!appointment.personName.isNullOrBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Person, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(appointment.personName, fontSize = 13.sp, color = TextSecondary)
                    }
                }
            }

            IconButton(onClick = onEditRequest) {
                Icon(Icons.Filled.Edit, contentDescription = "ویرایش", tint = VividPurple)
            }
            IconButton(onClick = onDeleteRequest) {
                Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = DangerRed)
            }
        }
    }
}

/** Small triangular "play" icon pointing right, drawn manually as requested. */
@Composable
private fun PlayTriangle() {
    Canvas(modifier = Modifier.size(16.dp)) {
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, size.height / 2f)
            lineTo(0f, size.height)
            close()
        }
        drawPath(path, color = androidx.compose.ui.graphics.Color.White)
    }
}
