package com.galaxy.assettrail.driver.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.galaxy.assettrail.driver.ui.theme.Palette
import com.galaxy.assettrail.driver.ui.theme.Type

@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    textStyle: TextStyle = Type.Body.copy(fontSize = Type.Subheading.fontSize, color = Palette.Text),
    isError: Boolean = false,
    enabled: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = Type.BodyStrong.copy(fontSize = Type.Small.fontSize.times(1.08f)), color = Palette.TextLabel)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                enabled = enabled,
                singleLine = true,
                isError = isError,
                textStyle = textStyle,
                shape = RoundedCornerShape(12.dp),
                placeholder = placeholder?.let { { Text(it, style = textStyle.copy(color = Palette.Upcoming)) } },
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    capitalization = capitalization,
                    imeAction = imeAction,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onAny = { onImeAction() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Palette.Text,
                    unfocusedTextColor = Palette.Text,
                    disabledTextColor = Palette.TextSecondary,
                    focusedContainerColor = Palette.Surface,
                    unfocusedContainerColor = Palette.Surface,
                    disabledContainerColor = Palette.Surface,
                    focusedBorderColor = Palette.Ember,
                    unfocusedBorderColor = Palette.OutlineButton,
                    disabledBorderColor = Palette.Border,
                    errorBorderColor = Palette.Alert,
                    errorContainerColor = Palette.Surface,
                    cursorColor = Palette.Ember,
                ),
            )
            trailing?.invoke(this)
        }
        if (hint != null) Text(hint, style = Type.Small, color = if (isError) Palette.Alert else Palette.TextSecondary)
    }
}
