package com.gromozeka.bot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography

@Composable
fun GromozekaTheme(content: @Composable () -> Unit) {
    val compactTypography = Typography(
        // Headers - максимум 24sp, пропорционально уменьшается
        displayLarge = MaterialTheme.typography.displayLarge.copy(fontSize = 24.sp, lineHeight = 28.sp),
        displayMedium = MaterialTheme.typography.displayMedium.copy(fontSize = 22.sp, lineHeight = 26.sp),
        displaySmall = MaterialTheme.typography.displaySmall.copy(fontSize = 20.sp, lineHeight = 24.sp),
        
        headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontSize = 20.sp, lineHeight = 24.sp),
        headlineMedium = MaterialTheme.typography.headlineMedium.copy(fontSize = 18.sp, lineHeight = 22.sp),
        headlineSmall = MaterialTheme.typography.headlineSmall.copy(fontSize = 16.sp, lineHeight = 20.sp),
        
        titleLarge = MaterialTheme.typography.titleLarge.copy(fontSize = 16.sp, lineHeight = 20.sp),
        titleMedium = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, lineHeight = 18.sp),
        titleSmall = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp, lineHeight = 17.sp),
        
        // Body text - основа 12sp
        bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.sp, lineHeight = 16.sp),
        bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
        bodySmall = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        
        // Labels - 11sp как есть
        labelLarge = MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp, lineHeight = 15.sp),
        labelMedium = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, lineHeight = 15.sp),
        labelSmall = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 15.sp)
    )

    MaterialTheme(
        typography = compactTypography,
        content = content
    )
}

// Compact Button Defaults
object CompactButtonDefaults {
    val ContentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
    val ButtonHeight = 28.dp
    val CornerRadius = 6.dp
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionalTooltip(
    tooltip: String?,
    content: @Composable () -> Unit
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = if (tooltip != null) {
            { PlainTooltip { Text(tooltip) } }
        } else {
            { }  // Empty composable
        },
        state = rememberTooltipState(),
        content = content
    )
}

@Composable
fun CompactButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tooltip: String? = null,
    contentPadding: PaddingValues = CompactButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    OptionalTooltip(tooltip) {
        Button(
            onClick = onClick,
            modifier = modifier.height(CompactButtonDefaults.ButtonHeight),
            enabled = enabled,
            contentPadding = contentPadding,
            shape = RoundedCornerShape(CompactButtonDefaults.CornerRadius),
            content = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    content = content
                )
            }
        )
    }
}

@Composable
fun CompactIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tooltip: String? = null,
    content: @Composable () -> Unit,
) {
    OptionalTooltip(tooltip) {
        FilledIconButton(
            onClick = onClick,
            modifier = modifier.size(CompactButtonDefaults.ButtonHeight),
            shape = RoundedCornerShape(CompactButtonDefaults.CornerRadius),
            enabled = enabled,
            content = content
        )
    }
}

@Composable
fun CompactCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(CompactButtonDefaults.CornerRadius),
        content = content
    )
}

@Composable
fun GromozekaMarkdown(
    content: String,
    modifier: Modifier = Modifier
) {
    Markdown(
        content = content,
        typography = markdownTypography(
            h1 = MaterialTheme.typography.displayMedium,    // 22sp (было 24sp)
            h2 = MaterialTheme.typography.displaySmall,     // 20sp (было 22sp)  
            h3 = MaterialTheme.typography.headlineMedium,   // 18sp (было 20sp)
            h4 = MaterialTheme.typography.headlineSmall,    // 16sp (было 18sp)
            h5 = MaterialTheme.typography.titleMedium,      // 14sp (было 16sp)
            h6 = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), // 12sp жирный
            text = MaterialTheme.typography.bodyMedium,     // 12sp
            paragraph = MaterialTheme.typography.bodyMedium, // 12sp
            code = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), // 12sp mono
            inlineCode = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace) // 12sp mono
        ),
        modifier = modifier
    )
}