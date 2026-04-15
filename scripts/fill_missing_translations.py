"""
Fill missing translations for language files that only have partial strings.
For the 4 files with only ~20 strings (af, in, pt-rBR, sv), we copy the full
English strings.xml and translate each string using deep_translator.
For other files with small gaps, we find missing keys and translate just those.
"""
import re, os, glob, time

try:
    from deep_translator import GoogleTranslator
    HAS_TRANSLATOR = True
except ImportError:
    HAS_TRANSLATOR = False
    print("WARNING: deep_translator not installed. Will copy English as fallback.")

ENGLISH_PATH = 'core/resources/src/main/res/values/strings.xml'

# Map Android locale folder suffix to Google Translate language code
LOCALE_MAP = {
    'af': 'af',
    'ar': 'ar',
    'de': 'de',
    'es': 'es',
    'fr': 'fr',
    'hi': 'hi',
    'id': 'id',
    'in': 'id',  # Android uses 'in' for Indonesian
    'it': 'it',
    'ja': 'ja',
    'ko': 'ko',
    'ml': 'ml',
    'nl': 'nl',
    'pt': 'pt',
    'pt-rBR': 'pt',
    'ru': 'ru',
    'sv': 'sv',
    'ta': 'ta',
    'te': 'te',
    'th': 'th',
    'tr': 'tr',
    'vi': 'vi',
    'zh-rCN': 'zh-CN',
    'zh-rTW': 'zh-TW',
}

def extract_strings(content):
    """Extract all string name->value pairs from XML content."""
    pattern = r'<string name="([^"]+)">(.*?)</string>'
    return dict(re.findall(pattern, content, re.DOTALL))

def translate_text(text, target_lang):
    """Translate text, preserving XML entities and format specifiers."""
    if not HAS_TRANSLATOR:
        return text
    if not text.strip():
        return text
    # Skip CDATA blocks - just return as-is with translation of inner text
    if '<![CDATA[' in text:
        return text  # Keep English for CDATA blocks to avoid XML breakage
    
    # Preserve format specifiers like %1$s, %1$d
    placeholders = re.findall(r'%\d+\$[sd]', text)
    temp_text = text
    for i, ph in enumerate(placeholders):
        temp_text = temp_text.replace(ph, f'__PH{i}__', 1)
    
    try:
        translated = GoogleTranslator(source='en', target=target_lang).translate(temp_text)
        if translated is None:
            return text
        # Restore placeholders
        for i, ph in enumerate(placeholders):
            translated = translated.replace(f'__PH{i}__', ph, 1)
            # Also try without underscores in case translator modified them
            translated = translated.replace(f'PH{i}', ph, 1)
        return translated
    except Exception as e:
        print(f"  Translation failed: {e}")
        return text

def escape_xml(text):
    """Escape single quotes for Android XML."""
    # Don't double-escape
    text = text.replace("\\'", "'")
    text = text.replace("'", "\\'")
    return text

# Read English strings
with open(ENGLISH_PATH, 'r', encoding='utf-8') as f:
    english_content = f.read()

english_strings = extract_strings(english_content)
print(f"English has {len(english_strings)} strings")

# Process each language file
for path in sorted(glob.glob('core/resources/src/main/res/values-*/strings.xml')):
    folder = os.path.basename(os.path.dirname(path))
    locale_suffix = folder.replace('values-', '')
    
    if locale_suffix not in LOCALE_MAP:
        print(f"Skipping {locale_suffix} - no Google Translate mapping")
        continue
    
    target_lang = LOCALE_MAP[locale_suffix]
    
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    existing_strings = extract_strings(content)
    missing_keys = set(english_strings.keys()) - set(existing_strings.keys())
    
    if not missing_keys:
        print(f"{locale_suffix}: Complete ({len(existing_strings)} strings)")
        continue
    
    print(f"{locale_suffix}: Missing {len(missing_keys)} strings, translating...")
    
    # Build the missing string entries
    new_entries = []
    for key in sorted(missing_keys):
        english_value = english_strings[key]
        translated = translate_text(english_value, target_lang)
        translated = escape_xml(translated)
        new_entries.append(f'    <string name="{key}">{translated}</string>')
        # Rate limit to avoid hitting API limits
        if HAS_TRANSLATOR:
            time.sleep(0.3)
    
    # Insert before </resources>
    insert_block = '\n'.join(new_entries)
    content = content.replace('</resources>', f'\n{insert_block}\n</resources>')
    
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)
    
    print(f"  Added {len(missing_keys)} translations to {locale_suffix}")

print("\nDone!")
