package com.pronote.clay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.pronote.clay.core.LoginUrl
import com.pronote.clay.core.SchoolSearch
import com.pronote.clay.core.SchoolSuggestion
import com.pronote.clay.data.remote.Diagnosis
import com.pronote.clay.ui.components.ClayButton
import com.pronote.clay.ui.components.ClayDivider
import com.pronote.clay.ui.components.ClayErrorBox
import com.pronote.clay.ui.components.ClayPill
import com.pronote.clay.ui.components.ClaySectionTitle
import com.pronote.clay.ui.components.ClaySurface
import com.pronote.clay.ui.components.clay
import com.pronote.clay.ui.components.clayInset
import com.pronote.clay.ui.theme.ClayDimens
import com.pronote.clay.ui.viewmodel.HomeUiState
import com.pronote.clay.ui.viewmodel.HomeViewModel
import com.pronote.clay.ui.viewmodel.SchoolPickerState

/**
 * LoginScreen — clay-styled credentials form.
 *
 * L'élève ne tape plus l'adresse de son établissement : il écrit le nom de sa
 * ville dans le sélecteur, choisit son collège ou son lycée dans la liste, et
 * n'a plus que ses identifiants PRONOTE à saisir. La saisie manuelle de
 * l'adresse reste disponible, pour les établissements qui passent par un ENT ou
 * qui n'apparaissent pas dans l'annuaire.
 *
 * Credentials are entered by the student and sent directly to the selected
 * school's Pronote server; no demo credentials are sent to a real account.
 */
@Composable
fun LoginScreen(viewModel: HomeViewModel) {
    val state by viewModel.state.collectAsState()
    val error = state as? HomeUiState.Error

    val draft by viewModel.loginDraft.collectAsState()
    val picked by viewModel.pickedSchool.collectAsState()
    val picker by viewModel.schoolPicker.collectAsState()
    val diagnosis by viewModel.diagnosis.collectAsState()
    val diagnosing by viewModel.diagnosing.collectAsState()
    var showPassword by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = ClayDimens.ScreenPadding),
    ) {
        Spacer(Modifier.height(32.dp))

        LoginHeader()

        Spacer(Modifier.height(34.dp))
        LoginFields(
            pickedSchool = picked,
            schoolUrl = draft.schoolUrl,
            username = draft.username,
            password = draft.password,
            error = error,
            showPassword = showPassword,
            onTogglePassword = { showPassword = !showPassword },
            onOpenPicker = { showPicker = true },
            onUsernameChange = { viewModel.updateLoginUsername(it) },
            onPasswordChange = { viewModel.updateLoginPassword(it) },
        )

        Spacer(Modifier.height(22.dp))
        ClayButton(
            text = "Se connecter",
            onClick = {
                val url = LoginUrl.normalize(draft.schoolUrl)
                if (url != null && draft.username.isNotBlank() && draft.password.isNotBlank()) {
                    viewModel.login(url, draft.username.trim(), draft.password) {}
                }
            },
            enabled = LoginUrl.isValid(draft.schoolUrl) &&
                draft.username.isNotBlank() && draft.password.isNotBlank(),
        )

        LoginHelpNote()

        Spacer(Modifier.height(40.dp))
    }

    if (showPicker) {
        SchoolPickerDialog(
            state = picker,
            schoolUrl = draft.schoolUrl,
            diagnosis = diagnosis,
            diagnosing = diagnosing,
            onQueryChange = viewModel::updateSchoolQuery,
            onSearch = viewModel::searchSchools,
            onSelect = { school ->
                viewModel.pickSchool(school)
                showPicker = false
            },
            onManualUrlChange = viewModel::updateLoginSchoolUrl,
            onDiagnose = viewModel::diagnoseConnection,
            // Le QR code de PRONOTE remplace la saisie de l'adresse : on garde
            // l'URL qu'il contient et on referme le panneau.
            onScanned = { url ->
                viewModel.updateLoginSchoolUrl(url)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun LoginFields(
    pickedSchool: SchoolSuggestion?,
    schoolUrl: String,
    username: String,
    password: String,
    error: HomeUiState.Error?,
    showPassword: Boolean,
    onTogglePassword: () -> Unit,
    onOpenPicker: () -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
) {
    ClaySectionTitle("Connexion")
    Spacer(Modifier.height(14.dp))

    SchoolTile(pickedSchool = pickedSchool, schoolUrl = schoolUrl, onOpen = onOpenPicker)
    Spacer(Modifier.height(12.dp))
    ClayField(
        value = username,
        onValueChange = onUsernameChange,
        label = "Identifiant",
        leading = { Icon(Icons.Rounded.Person, contentDescription = null, Modifier.size(18.dp)) },
        // Le gestionnaire de mots de passe Android reconnaît le champ et
        // propose l'identifiant enregistré.
        contentType = ContentType.Username,
    )
    Spacer(Modifier.height(12.dp))
    ClayField(
        value = password,
        onValueChange = onPasswordChange,
        label = "Mot de passe",
        leading = { Icon(Icons.Rounded.Lock, contentDescription = null, Modifier.size(18.dp)) },
        contentType = ContentType.Password,
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardType = KeyboardType.Password,
        trailing = {
            IconButton(onClick = onTogglePassword) {
                Icon(
                    if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (showPassword) "Masquer" else "Afficher",
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    )

    if (error != null) {
        Spacer(Modifier.height(16.dp))
        ClayErrorBox(message = error.message, hint = error.hint)
    }
}

/**
 * Le champ « établissement » : un bouton qui ouvre le sélecteur, qui affiche le
 * lycée choisi une fois la recherche faite.
 */
@Composable
private fun SchoolTile(
    pickedSchool: SchoolSuggestion?,
    schoolUrl: String,
    onOpen: () -> Unit,
) {
    val title = when {
        pickedSchool != null -> pickedSchool.name
        schoolUrl.isNotBlank() -> "Adresse saisie à la main"
        else -> "Choisir mon établissement"
    }
    val subtitle = when {
        pickedSchool != null -> pickedSchool.subtitle.ifBlank { pickedSchool.url }
        schoolUrl.isNotBlank() -> schoolUrl
        else -> "Le nom de ta ville suffit — ex. Ancenis"
    }

    ClaySurface(
        modifier = Modifier.fillMaxWidth(),
        depth = 6.dp,
        shape = MaterialTheme.shapes.large,
        onClick = onOpen,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.School, contentDescription = null, Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                if (pickedSchool != null || schoolUrl.isNotBlank()) Icons.Rounded.Edit else Icons.Rounded.Search,
                contentDescription = "Changer d'établissement",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Sélecteur d'établissement : la ville, la liste, et la porte de sortie
 * manuelle pour les établissements absents de l'annuaire.
 */
@Composable
private fun SchoolPickerDialog(
    state: SchoolPickerState,
    schoolUrl: String,
    diagnosis: Diagnosis?,
    diagnosing: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelect: (SchoolSuggestion) -> Unit,
    onManualUrlChange: (String) -> Unit,
    onDiagnose: (String) -> Unit,
    onScanned: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Filtre local par nom : la liste d'une ville compte parfois trente
    // établissements, taper « jouvence » va plus vite que de la parcourir.
    var nameFilter by remember { mutableStateOf("") }
    val context = LocalContext.current
    var scanFailed by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .safeDrawingPadding()
                .padding(horizontal = 18.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            ClaySurface(
                modifier = Modifier
                    .fillMaxWidth()
                    // Le toucher sur la carte ne doit pas la refermer.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                depth = 16.dp,
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(
                    Modifier
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        "Trouver mon établissement",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Écris le nom de ta ville, puis choisis ton collège ou ton lycée dans la liste.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))

                    ClayField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        label = "Ville (ex. Ancenis)",
                        // Le bouton-loupe, en plus de la touche « Rechercher » du
                        // clavier : sans lui, rien n'indiquait comment lancer la
                        // recherche.
                        leading = {
                            Icon(Icons.Rounded.Search, contentDescription = null, Modifier.size(18.dp))
                        },
                        trailing = {
                            IconButton(onClick = onSearch) {
                                Icon(
                                    Icons.Rounded.ArrowForward,
                                    contentDescription = "Rechercher cette ville",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Search,
                        onSearch = onSearch,
                    )
                    Spacer(Modifier.height(10.dp))

                    when {
                        state.searching -> Row(
                            Modifier.fillMaxWidth().padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Recherche des établissements…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        state.error != null -> Text(
                            state.error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        state.results.isNotEmpty() -> {
                            Column(Modifier.fillMaxWidth()) {
                                ClayField(
                                    value = nameFilter,
                                    onValueChange = { nameFilter = it },
                                    label = "Filtrer par nom (optionnel)",
                                    leading = {
                                        Icon(Icons.Rounded.Search, contentDescription = null, Modifier.size(18.dp))
                                    },
                                )
                                Spacer(Modifier.height(8.dp))
                                val filtered = SchoolSearch.filterByName(state.results, nameFilter)
                                if (filtered.isEmpty()) {
                                    Text(
                                        "Aucun établissement de cette ville ne contient « $nameFilter ».",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 280.dp)
                                            .verticalScroll(rememberScrollState()),
                                    ) {
                                        filtered.forEach { school ->
                                            SchoolResultRow(school = school, onClick = { onSelect(school) })
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    ClayDivider()
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Ton établissement n'est pas dans la liste ? Saisis son adresse PRONOTE " +
                            "(nécessaire quand la connexion passe par un ENT).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    ClayField(
                        value = schoolUrl,
                        onValueChange = onManualUrlChange,
                        label = "Adresse Pronote",
                        leading = {
                            Icon(Icons.Rounded.School, contentDescription = null, Modifier.size(18.dp))
                        },
                        keyboardType = KeyboardType.Uri,
                        supporting = if (schoolUrl.isNotBlank() && !LoginUrl.isValid(schoolUrl)) {
                            "Adresse incomplète — par exemple https://demo.index-education.net/pronote/"
                        } else {
                            null
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                onDiagnose(schoolUrl)
                            },
                            enabled = schoolUrl.isNotBlank() && !diagnosing,
                        ) {
                            Text(
                                if (diagnosing) "Vérification…" else "Vérifier cette adresse",
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        TextButton(
                            onClick = {
                                scanFailed = false
                                runCatching {
                                    val options = GmsBarcodeScannerOptions.Builder()
                                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                                        .build()
                                    GmsBarcodeScanning.getClient(context, options)
                                }.onSuccess { client ->
                                    client.startScan()
                                        .addOnSuccessListener { barcode ->
                                            val url = barcode.rawValue?.let { SchoolSearch.extractSchoolUrl(it) }
                                            if (url != null) onScanned(url) else scanFailed = true
                                        }
                                        .addOnFailureListener { scanFailed = true }
                                }.onFailure { scanFailed = true }
                            },
                        ) {
                            Icon(
                                Icons.Rounded.QrCodeScanner,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Scanner le QR code", color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    when {
                        scanFailed -> Text(
                            "Ce QR code n'a pas pu être lu. Dans PRONOTE sur l'ordinateur : " +
                                "Mes données > Paramètres > Code à flasher, ou copie l'adresse affichée.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        diagnosis != null -> DiagnosisBox(diagnosis)
                    }

                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) {
                            Text("Fermer", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** Résultat du diagnostic d'adresse, affiché sous le bouton qui l'a lancé. */
@Composable
private fun DiagnosisBox(diagnosis: Diagnosis) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Rounded.Info,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(diagnosis.message, style = MaterialTheme.typography.bodyMedium)
            diagnosis.hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            diagnosis.pronoteVersion?.let {
                Text(
                    "PRONOTE $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Une ligne de la liste : le nom de l'établissement et son code postal. */
@Composable
private fun SchoolResultRow(school: SchoolSuggestion, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                school.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (school.subtitle.isNotBlank()) {
                Text(
                    school.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.Rounded.School,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoginHeader() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .size(64.dp)
                .clay(depth = 14.dp, tint = MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.School,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text("Ardoise", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Tes notes, hors ligne, en un clin d'œil.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoginHelpNote() {
    Spacer(Modifier.height(26.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        ClayPill(text = "Connexion directe à Pronote", tint = MaterialTheme.colorScheme.tertiary)
    }
    Spacer(Modifier.height(10.dp))
    Text(
        "Tu choisis ton établissement, puis tes identifiants Pronote. " +
            "Ils sont transmis directement au serveur de ton établissement, pas à un serveur Ardoise. " +
            "Seul le jeton de session est conservé chiffré sur cet appareil. " +
            "Les connexions via un ENT ne sont pas prises en charge.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ClayField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    leading: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    supporting: String? = null,
    imeAction: ImeAction = ImeAction.Next,
    onSearch: (() -> Unit)? = null,
    /** Rôle autofill : le gestionnaire de mots de passe Android remplit le champ. */
    contentType: ContentType? = null,
) {
    val colors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
    )

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        leadingIcon = leading,
        trailingIcon = trailing,
        supportingText = supporting?.let { { Text(it) } },
        isError = supporting != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
        visualTransformation = visualTransformation,
        shape = MaterialTheme.shapes.large,
        colors = colors,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (contentType != null) {
                    Modifier.semantics { this.contentType = contentType }
                } else {
                    Modifier
                },
            )
            .clayInset(shape = MaterialTheme.shapes.large),
    )
}
