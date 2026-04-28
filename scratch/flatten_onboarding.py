import re

ONBOARDING_FILE = r"c:\Users\sudhi\.gemini\antigravity\scratch\GhostStream\feature\onboarding\src\main\java\com\ghoststream\feature\onboarding\OnboardingScreen.kt"

with open(ONBOARDING_FILE, 'r', encoding='utf-8') as f:
    content = f.read()

onboarding_old = r"""                    Card\(
                        modifier = Modifier\.fillMaxSize\(\),
                        shape = RoundedCornerShape\(28\.dp\),
                        colors = CardDefaults\.cardColors\(
                            containerColor = ghostPanelColor\(\),
                        \),
                        border = BorderStroke\(1\.dp, MaterialTheme\.colorScheme\.outlineVariant\),
                        elevation = CardDefaults\.cardElevation\(defaultElevation = 2\.dp\), // Subtle lift improves hierarchy without overpowering the content\.
                    \) \{
                        Column\("""
onboarding_new = """                    Column(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Column("""
content = re.sub(onboarding_old, onboarding_new, content, flags=re.MULTILINE)

with open(ONBOARDING_FILE, 'w', encoding='utf-8') as f:
    f.write(content)
print("Updated OnboardingScreen.kt successfully.")


NETWORK_FILE = r"c:\Users\sudhi\.gemini\antigravity\scratch\GhostStream\feature\networksetup\src\main\java\com\ghoststream\feature\networksetup\NetworkSetupScreen.kt"

with open(NETWORK_FILE, 'r', encoding='utf-8') as f:
    net_content = f.read()

net_old = r"""    Card\(
        modifier = Modifier\.fillMaxWidth\(\),
        shape = RoundedCornerShape\(24\.dp\),
        colors = CardDefaults\.cardColors\(containerColor = MaterialTheme\.colorScheme\.surface\),
        border = BorderStroke\(1\.dp, MaterialTheme\.colorScheme\.outlineVariant\),
    \) \{
        Column\(
            modifier = Modifier\.padding\(GhostSpacing\.heroCard\),"""
net_new = """    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = GhostSpacing.screenHorizontal),
    ) {
        Column(
            modifier = Modifier.padding(vertical = GhostSpacing.heroCard),"""
net_content = re.sub(net_old, net_new, net_content, flags=re.MULTILINE)

with open(NETWORK_FILE, 'w', encoding='utf-8') as f:
    f.write(net_content)
print("Updated NetworkSetupScreen.kt successfully.")
