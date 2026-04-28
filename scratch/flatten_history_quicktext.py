import re

# ------------- HISTORY SCREEN -------------
HISTORY_FILE = r"c:\Users\sudhi\.gemini\antigravity\scratch\GhostStream\feature\history\src\main\java\com\ghoststream\feature\history\HistoryScreen.kt"
with open(HISTORY_FILE, 'r', encoding='utf-8') as f:
    content = f.read()

# Replace HistoryItem Card
history_item_old = r"""    Card\(
        modifier = Modifier\.fillMaxWidth\(\),
        colors = CardDefaults\.cardColors\(
            containerColor = MaterialTheme\.colorScheme\.surface
        \),
        shape = RoundedCornerShape\(24\.dp\),
        border = BorderStroke\(1\.dp, MaterialTheme\.colorScheme\.outlineVariant\),
        elevation = CardDefaults\.cardElevation\(defaultElevation = 1\.dp\),
    \) \{"""
history_item_new = """    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {"""
content = re.sub(history_item_old, history_item_new, content, flags=re.MULTILINE)

# Replace EmptyHistoryState Card
empty_history_old = r"""        Card\(
            modifier = Modifier\.padding\(horizontal = 24\.dp\),
            shape = RoundedCornerShape\(24\.dp\),
            colors = CardDefaults\.cardColors\(containerColor = MaterialTheme\.colorScheme\.surface\),
            border = BorderStroke\(1\.dp, MaterialTheme\.colorScheme\.outlineVariant\),
            elevation = CardDefaults\.cardElevation\(defaultElevation = 1\.dp\),
        \) \{
            Column\(
                modifier = Modifier\.padding\(horizontal = 28\.dp, vertical = 24\.dp\),
                horizontalAlignment = Alignment\.CenterHorizontally,
            \) \{"""
empty_history_new = """        Column(
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {"""
content = re.sub(empty_history_old, empty_history_new, content, flags=re.MULTILINE)
# Fix the double closing brace for EmptyHistoryState
content = content.replace("Text(\n                text = message,\n                color = MaterialTheme.colorScheme.onSurfaceVariant\n            )\n            }\n        }", "Text(\n                text = message,\n                color = MaterialTheme.colorScheme.onSurfaceVariant\n            )\n        }")

# Update Top level Surface Tab row to make it borderless
tab_old = r"""            Surface\(
                modifier = Modifier
                    \.fillMaxWidth\(\)
                    \.padding\(
                        horizontal = GhostSpacing\.screenHorizontal,
                        vertical = 12\.dp,
                    \),
                shape = RoundedCornerShape\(20\.dp\),
                color = MaterialTheme\.colorScheme\.surfaceVariant,
                border = BorderStroke\(1\.dp, MaterialTheme\.colorScheme\.outlineVariant\),
            \) \{"""
tab_new = """            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = GhostSpacing.screenHorizontal,
                        vertical = 12.dp,
                    ),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {"""
content = re.sub(tab_old, tab_new, content, flags=re.MULTILINE)

with open(HISTORY_FILE, 'w', encoding='utf-8') as f:
    f.write(content)
print("Updated HistoryScreen.kt successfully.")

# ------------- QUICK TEXT SCREEN -------------
QT_FILE = r"c:\Users\sudhi\.gemini\antigravity\scratch\GhostStream\feature\history\src\main\java\com\ghoststream\feature\history\QuickTextScreen.kt"
with open(QT_FILE, 'r', encoding='utf-8') as f:
    content = f.read()

# Replace Send Card
send_card_old = r"""            Card\(
                modifier = Modifier
                    \.padding\(horizontal = GhostSpacing\.screenHorizontal\)
                    \.fillMaxWidth\(\),
                shape = RoundedCornerShape\(24\.dp\),
                colors = CardDefaults\.cardColors\(containerColor = MaterialTheme\.colorScheme\.surface\),
            \) \{
                Column\(
                    modifier = Modifier\.padding\(GhostSpacing\.card\),
                    verticalArrangement = Arrangement\.spacedBy\(14\.dp\),
                \) \{"""
send_card_new = """            Column(
                modifier = Modifier.padding(horizontal = GhostSpacing.screenHorizontal),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {"""
content = re.sub(send_card_old, send_card_new, content, flags=re.MULTILINE)
content = content.replace("draft = \"\"\n                        },\n                        enabled = draft.isNotBlank(),\n                        modifier = Modifier.fillMaxWidth(),\n                    ) {\n                        Text(stringResource(R.string.quick_text_send))\n                    }\n                }\n            }", "draft = \"\"\n                        },\n                        enabled = draft.isNotBlank(),\n                        modifier = Modifier.fillMaxWidth(),\n                    ) {\n                        Text(stringResource(R.string.quick_text_send))\n                    }\n            }")

# Replace QuickTextMessageCard
msg_card_old = r"""    Card\(
        modifier = Modifier
            \.padding\(horizontal = GhostSpacing\.screenHorizontal\)
            \.fillMaxWidth\(\),
        shape = RoundedCornerShape\(20\.dp\),
        colors = CardDefaults\.cardColors\(containerColor = MaterialTheme\.colorScheme\.surface\),
    \) \{
        Column\(
            modifier = Modifier\.padding\(GhostSpacing\.card\),
            verticalArrangement = Arrangement\.spacedBy\(10\.dp\),
        \) \{"""
msg_card_new = """    Column(
        modifier = Modifier.padding(horizontal = GhostSpacing.screenHorizontal, vertical = 12.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {"""
content = re.sub(msg_card_old, msg_card_new, content, flags=re.MULTILINE)

with open(QT_FILE, 'w', encoding='utf-8') as f:
    f.write(content)
print("Updated QuickTextScreen.kt successfully.")
