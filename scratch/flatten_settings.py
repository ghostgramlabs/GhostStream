import re

FILE_PATH = r"c:\Users\sudhi\.gemini\antigravity\scratch\GhostStream\feature\settings\src\main\java\com\ghoststream\feature\settings\SettingsScreen.kt"

with open(FILE_PATH, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Replace SettingsGroup
settings_group_old = r"""@Composable
private fun SettingsGroup\(title: String, content: @Composable \(\) -> Unit\) \{
    Card\(
        modifier = Modifier
            \.padding\(horizontal = GhostSpacing\.screenHorizontal\)
            \.fillMaxWidth\(\),
        shape = RoundedCornerShape\(24\.dp\),
        colors = CardDefaults\.cardColors\(containerColor = ghostPanelColor\(\)\),
        border = BorderStroke\(1\.dp, MaterialTheme\.colorScheme\.outline\),
        elevation = CardDefaults\.cardElevation\(defaultElevation = 1\.dp\),
    \) \{
        Column\(modifier = Modifier\.padding\(vertical = 12\.dp\)\) \{
            Text\(
                text = title,
                modifier = Modifier\.padding\(horizontal = GhostSpacing\.listItem, vertical = 8\.dp\),
                style = MaterialTheme\.typography\.titleLarge,
                fontWeight = FontWeight\.SemiBold,
            \)
            content\(\)
        \}
    \}
\}"""

settings_group_new = """@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GhostSpacing.screenHorizontal)
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = GhostSpacing.listItem, vertical = 8.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}"""
content = re.sub(settings_group_old, settings_group_new, content, flags=re.MULTILINE)


# 2. Replace HelpSectionCard
help_section_old = r"""@Composable
private fun HelpSectionCard\(
    title: String,
    lines: List<String>,
\) \{
    Card\(
        modifier = Modifier\.fillMaxWidth\(\),
        shape = RoundedCornerShape\(24\.dp\),
        colors = CardDefaults\.cardColors\(containerColor = ghostPanelColor\(\)\),
        border = BorderStroke\(1\.dp, MaterialTheme\.colorScheme\.outline\),
        elevation = CardDefaults\.cardElevation\(defaultElevation = 1\.dp\),
    \) \{
        Column\(
            modifier = Modifier\.padding\(GhostSpacing\.card\),
            verticalArrangement = Arrangement\.spacedBy\(10\.dp\),
        \) \{
            Text\(title, style = MaterialTheme\.typography\.titleMedium, fontWeight = FontWeight\.SemiBold, color = MaterialTheme\.colorScheme\.onSurface\)
            lines\.forEach \{ line ->
                Text\(
                    text = line,
                    style = MaterialTheme\.typography\.bodyMedium,
                    color = MaterialTheme\.colorScheme\.onSurfaceVariant,
                \)
            \}
        \}
    \}
\}"""

help_section_new = """@Composable
private fun HelpSectionCard(
    title: String,
    lines: List<String>,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = GhostSpacing.screenHorizontal, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        lines.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}"""
content = re.sub(help_section_old, help_section_new, content, flags=re.MULTILINE)

# 3. Replace HelpScreen Intro Card
help_intro_old = r"""Card\(
                modifier = Modifier\.fillMaxWidth\(\),
                shape = RoundedCornerShape\(28\.dp\),
                colors = CardDefaults\.cardColors\(containerColor = ghostPanelColor\(\)\),
                border = BorderStroke\(1\.dp, MaterialTheme\.colorScheme\.outline\),
                elevation = CardDefaults\.cardElevation\(defaultElevation = 1\.dp\), // Slight lift helps the intro card read as the primary entry section\.
            \) \{
                Column\(
                    modifier = Modifier\.padding\(GhostSpacing\.heroCard\),
                    verticalArrangement = Arrangement\.spacedBy\(14\.dp\),
                \) \{"""

help_intro_new = """Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = GhostSpacing.screenHorizontal),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {"""
content = re.sub(help_intro_old, help_intro_new, content, flags=re.MULTILINE)
# Add an extra } at the end of the replaced block
content = content.replace("Text(stringResource(R.string.help_about_3), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)\n                }\n            }", "Text(stringResource(R.string.help_about_3), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)\n            }")

# 4. Replace PrivacyPolicyScreen Intro Card
privacy_intro_old = r"""Card\(
                modifier = Modifier\.fillMaxWidth\(\),
                shape = RoundedCornerShape\(28\.dp\),
                colors = CardDefaults\.cardColors\(containerColor = ghostPanelColor\(\)\),
                border = BorderStroke\(1\.dp, MaterialTheme\.colorScheme\.outline\),
            \) \{
                Column\(
                    modifier = Modifier\.padding\(GhostSpacing\.heroCard\),
                    verticalArrangement = Arrangement\.spacedBy\(12\.dp\),
                \) \{"""

privacy_intro_new = """Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = GhostSpacing.screenHorizontal),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {"""
content = re.sub(privacy_intro_old, privacy_intro_new, content, flags=re.MULTILINE)
content = content.replace("Text(stringResource(R.string.privacy_policy_line_4), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)\n                }\n            }", "Text(stringResource(R.string.privacy_policy_line_4), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)\n            }")


# 5. Replace DlnaScreen Intro Card
dlna_intro_old = r"""Card\(
                modifier = Modifier\.fillMaxWidth\(\),
                shape = RoundedCornerShape\(28\.dp\),
                colors = CardDefaults\.cardColors\(containerColor = ghostPanelColor\(\)\),
                border = BorderStroke\(1\.dp, MaterialTheme\.colorScheme\.outline\),
                elevation = CardDefaults\.cardElevation\(defaultElevation = 1\.dp\),
            \) \{
                Column\(
                    modifier = Modifier\.padding\(GhostSpacing\.heroCard\),
                    verticalArrangement = Arrangement\.spacedBy\(14\.dp\),
                \) \{"""

dlna_intro_new = """Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = GhostSpacing.screenHorizontal),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {"""
content = re.sub(dlna_intro_old, dlna_intro_new, content, flags=re.MULTILINE)
content = content.replace("Text(\n                            text = if (dlnaEnabled) stringResource(R.string.dlna_toggle_off) else stringResource(R.string.dlna_toggle_on),\n                        )\n                    }\n                }\n            }", "Text(\n                        text = if (dlnaEnabled) stringResource(R.string.dlna_toggle_off) else stringResource(R.string.dlna_toggle_on),\n                    )\n                }\n            }")

# 6. Change all padding around the main content to match the new flat layout
content = content.replace("Arrangement.spacedBy(GhostSpacing.section)", "Arrangement.spacedBy(32.dp)")

with open(FILE_PATH, 'w', encoding='utf-8') as f:
    f.write(content)
print("Updated SettingsScreen.kt successfully.")
