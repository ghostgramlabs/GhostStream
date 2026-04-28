import re

SESSION_FILE = r"c:\Users\sudhi\.gemini\antigravity\scratch\GhostStream\feature\session\src\main\java\com\ghoststream\feature\session\ActiveSessionScreen.kt"

with open(SESSION_FILE, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. SessionHeroCard
hero_old = r"""    Card\(
        modifier = Modifier
            \.padding\(horizontal = GhostSpacing\.screenHorizontal\)
            \.fillMaxWidth\(\),
        shape = RoundedCornerShape\(28\.dp\),
        colors = CardDefaults\.cardColors\(containerColor = cardContainerColor\.value\),
        border = androidx\.compose\.foundation\.BorderStroke\(
            if \(sessionState\.isSharing\) 2\.dp else 1\.dp,
            if \(sessionState\.isSharing\) MaterialTheme\.colorScheme\.primary\.copy\(alpha = liveHeroPulse\) else cardBorderColor\.value,
        \),
    \) \{
        Column\(
            modifier = Modifier\.padding\(GhostSpacing\.card\),"""
hero_new = """    Column(
        modifier = Modifier.padding(horizontal = GhostSpacing.screenHorizontal).fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),"""
content = re.sub(hero_old, hero_new, content, flags=re.MULTILINE)


# 2. SessionLibraryRefreshCard
refresh_old = r"""    Card\(
        modifier = Modifier
            \.padding\(horizontal = GhostSpacing\.screenHorizontal\)
            \.fillMaxWidth\(\),
        shape = RoundedCornerShape\(22\.dp\),
        colors = CardDefaults\.cardColors\(containerColor = MaterialTheme\.colorScheme\.surface\),
        border = androidx\.compose\.foundation\.BorderStroke\(1\.dp, MaterialTheme\.colorScheme\.outline\),
    \) \{
        Column\(
            modifier = Modifier\.padding\(GhostSpacing\.card\),"""
refresh_new = """    Column(
        modifier = Modifier.padding(horizontal = GhostSpacing.screenHorizontal).fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),"""
content = re.sub(refresh_old, refresh_new, content, flags=re.MULTILINE)


# 3. SessionBrowserPrepCard
prep_old = r"""    Card\(
        modifier = Modifier
            \.padding\(horizontal = GhostSpacing\.screenHorizontal\)
            \.fillMaxWidth\(\),
        shape = RoundedCornerShape\(22\.dp\),
        colors = CardDefaults\.cardColors\(containerColor = MaterialTheme\.colorScheme\.surface\),
        border = androidx\.compose\.foundation\.BorderStroke\(1\.dp, MaterialTheme\.colorScheme\.outline\),
    \) \{
        Column\(
            modifier = Modifier\.padding\(GhostSpacing\.card\),"""
prep_new = """    Column(
        modifier = Modifier.padding(horizontal = GhostSpacing.screenHorizontal).fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),"""
content = re.sub(prep_old, prep_new, content, flags=re.MULTILINE)

# 4. ConnectedDevicesCard
devices_old = r"""    Card\(
        modifier = Modifier
            \.padding\(horizontal = GhostSpacing\.screenHorizontal\)
            \.fillMaxWidth\(\),
        shape = RoundedCornerShape\(24\.dp\),
        colors = CardDefaults\.cardColors\(containerColor = MaterialTheme\.colorScheme\.surface\),
        border = androidx\.compose\.foundation\.BorderStroke\(1\.dp, MaterialTheme\.colorScheme\.outline\),
        elevation = CardDefaults\.cardElevation\(defaultElevation = 1\.dp\),
    \) \{
        Column\(
            modifier = Modifier\.padding\(GhostSpacing\.card\),"""
devices_new = """    Column(
        modifier = Modifier.padding(horizontal = GhostSpacing.screenHorizontal).fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),"""
content = re.sub(devices_old, devices_new, content, flags=re.MULTILINE)

# 5. BlockedDevicesCard
blocked_old = r"""    Card\(
        modifier = Modifier
            \.padding\(horizontal = GhostSpacing\.screenHorizontal\)
            \.fillMaxWidth\(\),
        shape = RoundedCornerShape\(24\.dp\),
        colors = CardDefaults\.cardColors\(containerColor = MaterialTheme\.colorScheme\.surface\),
        border = androidx\.compose\.foundation\.BorderStroke\(1\.dp, MaterialTheme\.colorScheme\.outline\),
        elevation = CardDefaults\.cardElevation\(defaultElevation = 1\.dp\),
    \) \{
        Column\(
            modifier = Modifier\.padding\(GhostSpacing\.card\),"""
blocked_new = """    Column(
        modifier = Modifier.padding(horizontal = GhostSpacing.screenHorizontal).fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),"""
content = re.sub(blocked_old, blocked_new, content, flags=re.MULTILINE)

# 6. SessionStatsCard
stats_old = r"""    Card\(
        modifier = Modifier
            \.padding\(horizontal = GhostSpacing\.screenHorizontal\)
            \.fillMaxWidth\(\),
        shape = RoundedCornerShape\(24\.dp\),
        colors = CardDefaults\.cardColors\(containerColor = MaterialTheme\.colorScheme\.surface\),
        border = androidx\.compose\.foundation\.BorderStroke\(1\.dp, MaterialTheme\.colorScheme\.outline\),
        elevation = CardDefaults\.cardElevation\(defaultElevation = 1\.dp\),
    \) \{
        Row\("""
stats_new = """    Column(
        modifier = Modifier.padding(horizontal = GhostSpacing.screenHorizontal).fillMaxWidth(),
    ) {
        Row("""
content = re.sub(stats_old, stats_new, content, flags=re.MULTILINE)
# Also fix the inner padding of the Row inside SessionStatsRow
content = content.replace("modifier = Modifier\n                .fillMaxWidth()\n                .padding(GhostSpacing.card),", "modifier = Modifier\n                .fillMaxWidth(),")


with open(SESSION_FILE, 'w', encoding='utf-8') as f:
    f.write(content)

print("Updated ActiveSessionScreen.kt successfully.")

LIVE_FILE = r"c:\Users\sudhi\.gemini\antigravity\scratch\GhostStream\feature\session\src\main\java\com\ghoststream\feature\session\LiveScreenControlScreen.kt"

with open(LIVE_FILE, 'r', encoding='utf-8') as f:
    live_content = f.read()

live_old = r"""    Card\(
        modifier = Modifier
            \.padding\(horizontal = GhostSpacing\.screenHorizontal\)
            \.fillMaxWidth\(\),
        shape = RoundedCornerShape\(28\.dp\),
        colors = CardDefaults\.cardColors\(containerColor = MaterialTheme\.colorScheme\.surface\),
        border = androidx\.compose\.foundation\.BorderStroke\(1\.dp, MaterialTheme\.colorScheme\.outline\),
    \) \{
        Column\(
            modifier = Modifier\.padding\(GhostSpacing\.card\),"""
live_new = """    Column(
        modifier = Modifier.padding(horizontal = GhostSpacing.screenHorizontal).fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),"""
live_content = re.sub(live_old, live_new, live_content, flags=re.MULTILINE)

with open(LIVE_FILE, 'w', encoding='utf-8') as f:
    f.write(live_content)
print("Updated LiveScreenControlScreen.kt successfully.")
