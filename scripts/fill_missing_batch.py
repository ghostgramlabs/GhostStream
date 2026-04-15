"""
Batch-fill missing translations. Groups strings into chunks and translates
them together for speed. Uses separator-based batching.
"""
import re, os, glob, time

try:
    from deep_translator import GoogleTranslator
    HAS_TRANSLATOR = True
except ImportError:
    HAS_TRANSLATOR = False
    print("WARNING: deep_translator not installed.")

ENGLISH_PATH = 'core/resources/src/main/res/values/strings.xml'

LOCALE_MAP = {
    'af': 'af', 'ar': 'ar', 'de': 'de', 'es': 'es', 'fr': 'fr',
    'hi': 'hi', 'id': 'id', 'in': 'id', 'it': 'it', 'ja': 'ja',
    'ko': 'ko', 'ml': 'ml', 'nl': 'nl', 'pt': 'pt', 'pt-rBR': 'pt',
    'ru': 'ru', 'sv': 'sv', 'ta': 'ta', 'te': 'te', 'th': 'th',
    'tr': 'tr', 'vi': 'vi', 'zh-rCN': 'zh-CN', 'zh-rTW': 'zh-TW',
}

SEPARATOR = ' ||| '

def extract_strings(content):
    pattern = r'<string name="([^"]+)">(.*?)</string>'
    return dict(re.findall(pattern, content, re.DOTALL))

def batch_translate(texts, target_lang, chunk_size=30):
    """Translate a list of texts in batches using a separator."""
    if not HAS_TRANSLATOR or not texts:
        return texts
    
    results = []
    for i in range(0, len(texts), chunk_size):
        chunk = texts[i:i+chunk_size]
        # Filter out CDATA and complex entries - keep them as English
        simple_indices = []
        simple_texts = []
        chunk_results = list(chunk)  # start with originals
        
        for j, text in enumerate(chunk):
            if '<![CDATA[' in text or not text.strip():
                continue
            simple_indices.append(j)
            simple_texts.append(text)
        
        if simple_texts:
            joined = SEPARATOR.join(simple_texts)
            try:
                translated = GoogleTranslator(source='en', target=target_lang).translate(joined)
                if translated:
                    parts = translated.split('|||')
                    # Clean up whitespace around separators
                    parts = [p.strip() for p in parts]
                    if len(parts) == len(simple_texts):
                        for j, idx in enumerate(simple_indices):
                            chunk_results[idx] = parts[j]
                    else:
                        # Fallback: translate individually for this chunk
                        print(f"    Batch mismatch ({len(parts)} vs {len(simple_texts)}), falling back to individual...")
                        for j, idx in enumerate(simple_indices):
                            try:
                                t = GoogleTranslator(source='en', target=target_lang).translate(simple_texts[j])
                                if t:
                                    chunk_results[idx] = t
                                time.sleep(0.2)
                            except:
                                pass
            except Exception as e:
                print(f"    Batch translation failed: {e}")
        
        results.extend(chunk_results)
        time.sleep(0.5)
    
    return results

def escape_xml(text):
    text = text.replace("\\'", "'")
    text = text.replace("'", "\\'")
    return text

# Read English
with open(ENGLISH_PATH, 'r', encoding='utf-8') as f:
    english_content = f.read()

english_strings = extract_strings(english_content)
print(f"English has {len(english_strings)} strings\n")

for path in sorted(glob.glob('core/resources/src/main/res/values-*/strings.xml')):
    folder = os.path.basename(os.path.dirname(path))
    locale_suffix = folder.replace('values-', '')
    
    if locale_suffix not in LOCALE_MAP:
        print(f"Skipping {locale_suffix}")
        continue
    
    target_lang = LOCALE_MAP[locale_suffix]
    
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    existing = extract_strings(content)
    missing_keys = sorted(set(english_strings.keys()) - set(existing.keys()))
    
    if not missing_keys:
        print(f"{locale_suffix}: Complete ({len(existing)} strings)")
        continue
    
    print(f"{locale_suffix}: Missing {len(missing_keys)} strings, batch translating...")
    
    english_values = [english_strings[k] for k in missing_keys]
    translated_values = batch_translate(english_values, target_lang)
    
    new_entries = []
    for key, translated in zip(missing_keys, translated_values):
        translated = escape_xml(translated)
        new_entries.append(f'    <string name="{key}">{translated}</string>')
    
    insert_block = '\n'.join(new_entries)
    content = content.replace('</resources>', f'\n{insert_block}\n</resources>')
    
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)
    
    print(f"  Added {len(missing_keys)} translations to {locale_suffix}")

print("\nDone!")
