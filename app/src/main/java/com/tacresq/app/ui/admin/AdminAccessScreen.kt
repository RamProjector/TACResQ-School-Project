package com.tacresq.app.ui.admin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tacresq.app.data.remote.AdminAuditEvent
import com.tacresq.app.data.remote.AdminUser
import com.tacresq.app.data.model.User
import com.tacresq.app.data.repo.TacRepository
import com.tacresq.app.ui.common.ScreenHeader
import com.tacresq.app.ui.common.StatusPill
import kotlinx.coroutines.launch

private val adminRoles = listOf("requester", "responder", "driver", "dispatcher")

@Composable
fun AdminAccessScreen(
    user: User,
    repository: TacRepository,
    onSignOut: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf("Users") }
    var selectedRole by remember { mutableStateOf("all") }
    var query by remember { mutableStateOf("") }
    var users by remember { mutableStateOf<List<AdminUser>>(emptyList()) }
    var audit by remember { mutableStateOf<List<AdminAuditEvent>>(emptyList()) }
    var totalCount by remember { mutableStateOf(0L) }
    var offset by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var selectedUser by remember { mutableStateOf<AdminUser?>(null) }
    var targetRole by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun loadUsers(reset: Boolean = true) {
        scope.launch {
            loading = true
            error = null
            if (reset) offset = 0
            val pageOffset = if (reset) 0 else offset
            repository.adminUsersPage(limit = 25, offset = pageOffset, query = query, role = selectedRole)
                .onSuccess { page ->
                    users = if (reset) page.users else users + page.users
                    totalCount = page.totalCount
                    offset = pageOffset + page.users.size
                }
                .onFailure { error = it.message ?: "Unable to load the administrator user directory." }
            loading = false
        }
    }

    fun loadAudit() {
        scope.launch {
            loading = true
            error = null
            repository.adminAuditLogs(100)
                .onSuccess { audit = it }
                .onFailure { error = it.message ?: "Unable to load audit history." }
            loading = false
        }
    }

    LaunchedEffect(user.id) { loadUsers() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenHeader(
                title = "Admin",
                subtitle = "Manage accounts and review activity.",
                onLogout = onSignOut,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = selectedTab == "Users", onClick = {
                    selectedTab = "Users"
                    loadUsers()
                }, label = { Text("Users") })
                FilterChip(selected = selectedTab == "Audit", onClick = {
                    selectedTab = "Audit"
                    loadAudit()
                }, label = { Text("Audit history") })
            }

            if (selectedTab == "Users") {
                Card(
                    shape = MaterialTheme.shapes.large,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("User directory", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("$totalCount accounts", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = query, onValueChange = { if (it.length <= 120) query = it },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("Search accounts") },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            listOf("all", "requester", "responder", "driver", "dispatcher", "admin").forEach { role ->
                                FilterChip(
                                    selected = selectedRole == role,
                                    onClick = { selectedRole = role; loadUsers() },
                                    label = { Text(role.replaceFirstChar { it.uppercase() }) },
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { loadUsers() }, enabled = !loading) { Text("Search") }
                            OutlinedButton(onClick = { query = ""; selectedRole = "all"; loadUsers() }, enabled = !loading) {
                                Text("Clear")
                            }
                        }
                    }
                }

                if (loading && users.isEmpty()) CircularProgressIndicator()
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(users, key = { it.id }) { account ->
                        Card(
                            shape = MaterialTheme.shapes.large,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(account.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(account.email, style = MaterialTheme.typography.bodySmall)
                                Text("ID ${account.id}", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StatusPill(account.role.replaceFirstChar { it.uppercase() }, MaterialTheme.colorScheme.primary)
                                    account.dutyStatus?.let { StatusPill(it.replaceFirstChar { ch -> ch.uppercase() }, MaterialTheme.colorScheme.secondary) }
                                }
                                if (account.role != "admin") {
                                    TextButton(onClick = {
                                        selectedUser = account
                                        targetRole = ""
                                        reason = ""
                                    }) { Text("Change role") }
                                } else {
                                    Text("Admin access is managed outside the app.",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (users.isEmpty() && !loading && error == null) {
                        item { Text("No matching accounts found.", modifier = Modifier.padding(12.dp)) }
                    }
                    if (offset < totalCount) {
                        item {
                            OutlinedButton(onClick = { loadUsers(reset = false) }, enabled = !loading,
                                modifier = Modifier.fillMaxWidth()) { Text("Load more accounts") }
                        }
                    }
                }
            } else {
                Text("Recent administrative actions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (loading && audit.isEmpty()) CircularProgressIndicator()
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(audit, key = { it.id }) { event ->
                        AuditEventCard(event)
                    }
                    if (audit.isEmpty() && !loading && error == null) {
                        item { Text("No administrative actions have been recorded.") }
                    }
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            if (loading) Text("Refreshing…", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    selectedUser?.let { account ->
        AlertDialog(
            onDismissRequest = { selectedUser = null },
            title = { Text("Change account role") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("${account.name} · currently ${account.role}", fontWeight = FontWeight.SemiBold)
                    Text("${account.email} · Account ID ${account.id}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Select the intended operational role. No role is preselected. Admin promotion and demotion are not available in the app.")
                    adminRoles.forEach { role ->
                        FilterChip(selected = targetRole == role, onClick = { targetRole = role },
                            label = { Text(role.replaceFirstChar { it.uppercase() }) })
                    }
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { if (it.length <= 500) reason = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        label = { Text("Audit reason (required)") },
                        isError = reason.isNotBlank() && reason.trim().length < 10,
                        supportingText = {
                            when {
                                reason.isBlank() -> Text("Required: explain why this role change is necessary.")
                                reason.trim().length < 10 -> Text("Add at least ${10 - reason.trim().length} more characters.")
                                else -> Text("${reason.trim().length}/500 · This explanation is saved in the audit history.")
                            }
                        },
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = targetRole != account.role && reason.trim().length in 10..500 && !loading,
                    onClick = {
                        scope.launch {
                            loading = true
                            error = null
                            repository.setAdminUserRole(account.id, targetRole, reason.trim())
                                .onSuccess {
                                    notice = "${account.name}'s role was updated and the action was audited."
                                    selectedUser = null
                                    loadUsers()
                                }
                                .onFailure { error = it.message ?: "Role change was rejected." }
                            loading = false
                        }
                    },
                ) { Text(if (targetRole in adminRoles) "Confirm ${targetRole.replaceFirstChar { it.uppercase() }} role" else "Select a role") }
            },
            dismissButton = { TextButton(onClick = { selectedUser = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AuditEventCard(event: AdminAuditEvent) {
    Card(
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(event.action.replace("_", " ").replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Account #${event.targetUserId}: ${event.oldRole} → ${event.newRole}")
            Text("By ${event.actorIdentity} · ${event.createdAt}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Reason: ${event.reason}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
