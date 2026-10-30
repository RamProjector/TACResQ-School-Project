package com.tacresq.app.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.tacresq.app.data.model.AdminAuditEvent
import com.tacresq.app.data.model.AdminUserRecord
import com.tacresq.app.data.model.Role
import com.tacresq.app.data.model.User
import com.tacresq.app.data.repo.TacRepository
import kotlinx.coroutines.launch

private val managedRoles = listOf("requester", "responder", "driver", "dispatcher")

/**
 * Administrator-only console. Every read/write is sent to a SECURITY DEFINER RPC
 * that independently verifies auth.uid() against the current admin profile.
 */
@Composable
fun AdminAccessScreen(
    user: User,
    repository: TacRepository,
    onSignOut: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf<List<AdminUserRecord>>(emptyList()) }
    var audit by remember { mutableStateOf<List<AdminAuditEvent>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var showAudit by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var appliedQuery by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(0) }
    var totalCount by remember { mutableStateOf(0) }
    var selectedUser by remember { mutableStateOf<AdminUserRecord?>(null) }
    var selectedRole by remember { mutableStateOf("requester") }
    var roleMenuExpanded by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    val pageSize = 25

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            // Do not trust the role cached in the persisted Android session.
            val current = repository.me()
            val currentUser = current.getOrNull()
            if (currentUser == null || currentUser.role != Role.ADMIN) {
                users = emptyList()
                audit = emptyList()
                totalCount = 0
                loading = false
                error = current.exceptionOrNull()?.message
                    ?: "Administrator access is no longer active. Sign in again with an authorized account."
                if (currentUser?.role != Role.ADMIN) onSignOut()
                return@launch
            }
            val directory = repository.adminUsersPage(pageSize, page * pageSize, appliedQuery, "all")
            val logs = repository.adminAuditLogs(100)
            if (directory.isSuccess && logs.isSuccess) {
                users = directory.getOrThrow().users
                totalCount = directory.getOrThrow().totalCount
                audit = logs.getOrThrow()
            } else {
                // Fail closed: don't leave previously loaded privileged data visible after an RPC failure.
                users = emptyList()
                audit = emptyList()
                totalCount = 0
                error = directory.exceptionOrNull()?.message
                    ?: logs.exceptionOrNull()?.message
                    ?: "The administrator service could not load its data."
            }
            loading = false
        }
    }

    LaunchedEffect(user.id) { refresh() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("TACResQ · ADMIN", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text("Admin console", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Signed in as ${user.name}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onSignOut) { Text("Sign out") }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Privileged access", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("User directory and audit history are read through protected database functions. Role changes are checked and recorded by the database.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Administrator accounts cannot be created or changed from this console.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showAudit) OutlinedButton(onClick = { showAudit = false }) { Text("Users") }
                else Button(onClick = { showAudit = false }) { Text("Users") }
                if (showAudit) Button(onClick = { showAudit = true }) { Text("Audit log") }
                else OutlinedButton(onClick = { showAudit = true }) { Text("Audit log") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { refresh() }, enabled = !loading && !saving) { Text("Refresh") }
            }

            if (!showAudit) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(120) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search users") },
                    supportingText = { Text("Name, email, phone or profile ID") },
                    singleLine = true,
                )
                Button(
                    onClick = { appliedQuery = query.trim(); page = 0; refresh() },
                    enabled = !loading && !saving,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Search directory") }
                Text("Showing ${if (totalCount == 0) 0 else page * pageSize + 1}–${minOf((page + 1) * pageSize, totalCount)} of $totalCount profiles", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (error != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Admin data unavailable", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                        Text(error!!, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { refresh() }, enabled = !loading) { Text("Try again") }
                    }
                }
            }
            if (notice != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) { Text(notice!!, color = MaterialTheme.colorScheme.primary) }
                }
            }
            if (loading) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            } else if (error == null && showAudit) {
                Text("Latest ${audit.size} administrative events", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (audit.isEmpty()) Text("No administrative events have been recorded.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                audit.forEach { event ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(event.action.replace('_', ' ').replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold)
                            Text("Actor: ${event.actorIdentity}")
                            Text("Target profile: ${event.targetUserId?.toString() ?: "Unavailable"}")
                            Text("Role: ${event.oldRole ?: "—"} → ${event.newRole ?: "—"}")
                            Text("Reason: ${event.reason ?: "Not supplied"}")
                            Text(event.createdAt ?: "Timestamp unavailable", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else if (error == null) {
                Text("User management", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (users.isEmpty()) Text("No matching user profiles.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                users.forEach { profile ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(profile.email, style = MaterialTheme.typography.bodyMedium)
                            Text("Profile #${profile.id} · ${profile.role.replaceFirstChar { it.uppercase() }}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            profile.phone?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            if (profile.role == "admin") {
                                Text("Protected administrator account", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        selectedUser = profile
                                        selectedRole = profile.role
                                        reason = ""
                                        error = null
                                    },
                                    enabled = !saving,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Change operational role") }
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    OutlinedButton(onClick = { page = (page - 1).coerceAtLeast(0); refresh() }, enabled = page > 0 && !loading) { Text("Previous") }
                    OutlinedButton(onClick = { page += 1; refresh() }, enabled = (page + 1) * pageSize < totalCount && !loading) { Text("Next") }
                }
            }
        }
    }

    val target = selectedUser
    if (target != null) {
        AlertDialog(
            onDismissRequest = { if (!saving) selectedUser = null },
            title = { Text("Change account role") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${target.name} · ${target.email}")
                    Text("Choose an operational role. Admin privileges cannot be assigned here.")
                    OutlinedButton(onClick = { roleMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Role: ${selectedRole.replaceFirstChar { it.uppercase() }}")
                    }
                    DropdownMenu(expanded = roleMenuExpanded, onDismissRequest = { roleMenuExpanded = false }) {
                        managedRoles.forEach { role ->
                            DropdownMenuItem(text = { Text(role.replaceFirstChar { it.uppercase() }) }, onClick = { selectedRole = role; roleMenuExpanded = false })
                        }
                    }
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it.take(500) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Audit reason (required)") },
                        supportingText = { Text("${reason.trim().length}/500 · minimum 10 characters") },
                        minLines = 3,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            saving = true
                            error = null
                            val result = repository.adminSetUserRole(target.id, selectedRole, reason)
                            if (result.isSuccess) {
                                val changed = result.getOrThrow()
                                notice = "${changed.name}'s role changed from ${changed.oldRole} to ${changed.role}. The database recorded the action."
                                selectedUser = null
                                refresh()
                            } else {
                                error = result.exceptionOrNull()?.message ?: "The role change was rejected."
                            }
                            saving = false
                        }
                    },
                    enabled = !saving && selectedRole != target.role && reason.trim().length in 10..500,
                ) { Text(if (saving) "Saving…" else "Save role") }
            },
            dismissButton = { TextButton(onClick = { selectedUser = null }, enabled = !saving) { Text("Cancel") } },
        )
    }
}
