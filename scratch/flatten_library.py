import re

LIB_FILE = r"c:\Users\sudhi\.gemini\antigravity\scratch\GhostStream\feature\library\src\main\java\com\ghoststream\feature\library\SharedLibraryScreen.kt"

with open(LIB_FILE, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. LibraryImportingCard
import_old = r"""    Card\(
        modifier = Modifier
            \.padding\(horizontal = GhostSpacing\.screenHorizontal\)
            \.fillMaxWidth\(\),
        shape = RoundedCornerShape\(20\.dp\),
        colors = CardDefaults\.cardColors\(containerColor = MaterialTheme\.colorScheme\.surface\),
        border = BorderStroke\(1\.dp, MaterialTheme\.colorScheme\.outline\),
    \) \{
        Row\(
            modifier = Modifier\.padding\(GhostSpacing\.card\),"""
import_new = """    Column(
        modifier = Modifier.padding(horizontal = GhostSpacing.screenHorizontal).fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),"""
content = re.sub(import_old, import_new, content, flags=re.MULTILINE)

# 2. LibraryHeader
header_old = r"""    Card\(
        modifier = Modifier
            \.padding\(horizontal = GhostSpacing\.screenHorizontal\)
            \.fillMaxWidth\(\),
        shape = RoundedCornerShape\(28\.dp\),
        colors = CardDefaults\.cardColors\(containerColor = MaterialTheme\.colorScheme\.surface\),
        border = BorderStroke\(1\.dp, MaterialTheme\.colorScheme\.outline\),
        elevation = CardDefaults\.cardElevation\(defaultElevation = 1\.dp\),
    \) \{
        Column\(
            modifier = Modifier\.padding\(GhostSpacing\.card\),"""
header_new = """    Column(
        modifier = Modifier.padding(horizontal = GhostSpacing.screenHorizontal).fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),"""
content = re.sub(header_old, header_new, content, flags=re.MULTILINE)

# 3. LibraryEmptyState
empty_old = r"""    Card\(
        modifier = Modifier
            \.padding\(horizontal = GhostSpacing\.screenHorizontal\)
            \.fillMaxWidth\(\),
        shape = RoundedCornerShape\(24\.dp\),
        colors = CardDefaults\.cardColors\(containerColor = MaterialTheme\.colorScheme\.surface\),
        border = BorderStroke\(1\.dp, MaterialTheme\.colorScheme\.outline\),
    \) \{
        Column\(
            modifier = Modifier\.padding\(GhostSpacing\.card\),"""
empty_new = """    Column(
        modifier = Modifier.padding(horizontal = GhostSpacing.screenHorizontal).fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),"""
content = re.sub(empty_old, empty_new, content, flags=re.MULTILINE)

# 4. LibraryControlsCard
controls_old = r"""    Card\(
        modifier = Modifier
            \.padding\(horizontal = GhostSpacing\.screenHorizontal\)
            \.fillMaxWidth\(\),
        shape = RoundedCornerShape\(24\.dp\),
        colors = CardDefaults\.cardColors\(containerColor = MaterialTheme\.colorScheme\.surface\),
        border = BorderStroke\(1\.dp, MaterialTheme\.colorScheme\.outline\),
        elevation = CardDefaults\.cardElevation\(defaultElevation = 1\.dp\),
    \) \{
        Column\(
            modifier = Modifier\.padding\(GhostSpacing\.card\),"""
controls_new = """    Column(
        modifier = Modifier.padding(horizontal = GhostSpacing.screenHorizontal).fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),"""
content = re.sub(controls_old, controls_new, content, flags=re.MULTILINE)

# 5. LibraryItemRow
item_old = r"""    Card\(
        modifier = Modifier
            \.padding\(horizontal = GhostSpacing\.screenHorizontal\)
            \.fillMaxWidth\(\),
        shape = RoundedCornerShape\(22\.dp\),
        colors = CardDefaults\.cardColors\(
            containerColor = MaterialTheme\.colorScheme\.surface,
        \),
            border = BorderStroke\(1\.dp, MaterialTheme\.colorScheme\.outline\),
        elevation = CardDefaults\.cardElevation\(defaultElevation = 1\.dp\),
    \) \{
        BoxWithConstraints \{
            val compactActions = maxWidth < 520\.dp
            Column\(
                modifier = Modifier\.padding\(16\.dp\),"""
item_new = """    Column(
        modifier = Modifier.padding(horizontal = GhostSpacing.screenHorizontal).fillMaxWidth(),
    ) {
        BoxWithConstraints {
            val compactActions = maxWidth < 520.dp
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),"""
content = re.sub(item_old, item_new, content, flags=re.MULTILINE)

# FolderRow
folder_old = r"""    Surface\(
        shape = RoundedCornerShape\(20\.dp\),
        color = MaterialTheme\.colorScheme\.surface,
        border = BorderStroke\(1\.dp, MaterialTheme\.colorScheme\.outline\),
        tonalElevation = 1\.dp,
    \) \{
        Row\(
            modifier = Modifier
                \.fillMaxWidth\(\)
                \.padding\(16\.dp\),"""
folder_new = """    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),"""
content = re.sub(folder_old, folder_new, content, flags=re.MULTILINE)

with open(LIB_FILE, 'w', encoding='utf-8') as f:
    f.write(content)

print("Updated SharedLibraryScreen.kt successfully.")

BATCH_FILE = r"c:\Users\sudhi\.gemini\antigravity\scratch\GhostStream\feature\library\src\main\java\com\ghoststream\feature\library\BatchSelectScreen.kt"

with open(BATCH_FILE, 'r', encoding='utf-8') as f:
    batch_content = f.read()

batch_old = r"""    Card\(
        modifier = Modifier\.padding\(horizontal = GhostSpacing\.screenHorizontal\),
        shape = RoundedCornerShape\(24\.dp\),
        colors = CardDefaults\.cardColors\(containerColor = MaterialTheme\.colorScheme\.surface\),
        border = BorderStroke\(1\.dp, MaterialTheme\.colorScheme\.outline\),
    \) \{
        Column\(
            modifier = Modifier\.padding\(GhostSpacing\.card\),"""
batch_new = """    Column(
        modifier = Modifier.padding(horizontal = GhostSpacing.screenHorizontal),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),"""
batch_content = re.sub(batch_old, batch_new, batch_content, flags=re.MULTILINE)

with open(BATCH_FILE, 'w', encoding='utf-8') as f:
    f.write(batch_content)
print("Updated BatchSelectScreen.kt successfully.")
