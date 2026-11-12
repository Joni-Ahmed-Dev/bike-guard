package com.bikeguard.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.bikeguard.app.R
import com.bikeguard.app.ui.AppViewModel
import com.bikeguard.app.ui.theme.Palette

@Composable
fun AuthScreen(vm: AppViewModel) {
    var signUp by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPw by remember { mutableStateOf(false) }
    val canSubmit = email.contains("@") && password.length >= 6 && !vm.busy

    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(Palette.Ember.copy(alpha = 0.18f), Palette.Ink), radius = 900f)
        )
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(64.dp).background(Palette.Ember.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Place, null, tint = Palette.Ember, modifier = Modifier.size(32.dp)) }
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(if (signUp) R.string.auth_title_up else R.string.auth_title_in),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.auth_subtitle), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(32.dp))

            OutlinedTextField(
                value = email, onValueChange = { email = it }, singleLine = true,
                label = { Text(stringResource(R.string.email)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it }, singleLine = true,
                label = { Text(stringResource(R.string.password)) },
                supportingText = if (signUp) ({ Text(stringResource(R.string.password_hint)) }) else null,
                visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPw = !showPw }) {
                        Icon(if (showPw) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                    }
                },
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            )

            vm.error?.let { Spacer(Modifier.height(12.dp)); Text(it, color = Palette.Danger) }
            vm.info?.let { Spacer(Modifier.height(12.dp)); Text(it, color = Palette.Signal) }
            if (!vm.configured) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.config_missing), color = Palette.Danger, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { vm.submit(email, password, signUp) },
                enabled = canSubmit,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                if (vm.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = Palette.Ink)
                else Text(stringResource(if (signUp) R.string.sign_up else R.string.sign_in), style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = { signUp = !signUp }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(if (signUp) R.string.switch_to_in else R.string.switch_to_up), color = Palette.Muted)
            }
        }
    }
}
