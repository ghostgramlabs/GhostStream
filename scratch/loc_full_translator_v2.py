import os
import xml.etree.ElementTree as ET
import re
import time
import socket
socket.setdefaulttimeout(15)
from deep_translator import GoogleTranslator
from concurrent.futures import ThreadPoolExecutor, as_completed
import traceback

LANG_MAP = {
    "values-af": "af", "values-ar": "ar", "values-de": "de", "values-el": "el",
    "values-es": "es", "values-es-rUS": "es", "values-fr": "fr", "values-hi": "hi",
    "values-id": "id", "values-in": "id", "values-it": "it", "values-ja": "ja",
    "values-ko": "ko", "values-ml": "ml", "values-nl": "nl", "values-pt": "pt",
    "values-pt-rBR": "pt", "values-ru": "ru", "values-sv": "sv", "values-ta": "ta",
    "values-te": "te", "values-th": "th", "values-tr": "tr", "values-vi": "vi",
    "values-zh-rCN": "zh-CN", "values-zh-rTW": "zh-TW"
}

PROTECTED_TERMS = [
    "DirectServe", "GhostStream", "GhostGram", "Wi-Fi", "Wi-Fi", "hotspot",
    "HTTP", "HLS", "MP4", "WebRTC", "URL", "IP", "TV", "QR", "DLNA"
]

def load_xml(path):
    try:
        root = ET.parse(path).getroot()
        strings = {child.get('name'): child.text for child in root if child.tag == 'string'}
        plurals = {child.get('name'): {item.get('quantity'): item.text for item in child} for child in root if child.tag == 'plurals'}
        return strings, plurals
    except:
        return {}, {}

def protect_text(text):
    if not text: return text, {}, {}
    
    placeholders = {}
    terms = {}
    
    # Protect format specifiers (%1$d, %s, etc)
    ph_matches = re.finditer(r'%(\d+\$)?[-#+ 0,(\.<]*\d*(\.\d+)?[a-zA-Z%]', text)
    temp_text = text
    for i, m in enumerate(ph_matches):
        if m.group(0) == '%%': continue
        key = f"__P{i}__"
        placeholders[key] = m.group(0)
        temp_text = temp_text.replace(m.group(0), key)
        
    # Protect brand and tech terms
    for i, term in enumerate(PROTECTED_TERMS):
        pattern = re.compile(r'\b' + re.escape(term) + r'\b', re.IGNORECASE)
        matches = pattern.findall(temp_text)
        for m in set(matches):
            key = f"__T{i}_{len(terms)}__"
            terms[key] = "DirectServe" if term.lower() in ["ghoststream", "ghostgram"] else m
            temp_text = temp_text.replace(m, key)
            
    return temp_text, placeholders, terms

def unprotect_text(text, placeholders, terms):
    if not text: return text
    res = text
    # Sometimes Google Translate adds spaces around our tokens: __ P0 __
    res = re.sub(r'__\s+P(\d+)\s+__', r'__P\1__', res)
    res = re.sub(r'__\s+T(\d+)_(\d+)\s+__', r'__T\1_\2__', res)
    
    for key, val in terms.items():
        res = res.replace(key, val)
    for key, val in placeholders.items():
        res = res.replace(key, val)
    return res

def robust_translate_batch(translator, batch, max_retries=5):
    for attempt in range(max_retries):
        try:
            return translator.translate_batch(batch)
        except Exception as e:
            wait_time = (attempt + 1) * 5
            print(f"    [WARN] Translation batch failed (attempt {attempt+1}/{max_retries}). Retrying in {wait_time}s... Error: {e}")
            time.sleep(wait_time)
            
    # If all retries fail, return the original batch
    print(f"    [ERROR] Batch translation permanently failed after {max_retries} attempts.")
    return batch

def translate_in_batches(translator, items_to_trans, batch_size=40):
    results = {}
    texts = []
    metadata = []
    
    for item in items_to_trans:
        protected_text, ph, terms = protect_text(item[1])
        texts.append(protected_text)
        metadata.append((item, ph, terms))
        
    for i in range(0, len(texts), batch_size):
        batch = texts[i:i+batch_size]
        batch_meta = metadata[i:i+batch_size]
        
        print(f"    - Translating batch {i//batch_size + 1}/{(len(texts)//batch_size)+1}...", flush=True)
        translated_batch = robust_translate_batch(translator, batch)
        
        for j, translated in enumerate(translated_batch):
            item, ph, terms = batch_meta[j]
            if translated:
                final_str = unprotect_text(translated, ph, terms)
                results_key = f"{item[0]}:{item[3]}" if item[2] == 'plural' else item[0]
                results[results_key] = final_str
        time.sleep(1) # Grace period between batches
    return results

def process_locale(folder, res_dir, base_strings, base_plurals):
    skip_keys = ['common_separator_pipe', 'common_separator_dot', 'onboarding_page_counter', 'home_brand_title', 'splash_title', 'browser_title', 'dlna_server_name']
    
    if folder not in LANG_MAP: return
    locale_path = os.path.join(res_dir, folder, "strings.xml")
    translator = GoogleTranslator(source='en', target=LANG_MAP[folder])
    
    print(f"[{folder}] Starting full translation (Target: {LANG_MAP[folder]})...", flush=True)
    
    to_translate = []
    for key, text in base_strings.items():
        if not text: continue
        if key in skip_keys: continue
        to_translate.append((key, text, 'string', None))
            
    for key, items in base_plurals.items():
        for qty, text in items.items():
            if not text: continue
            to_translate.append((key, text, 'plural', qty))
                
    translated_results = {}
    if to_translate:
        translated_results = translate_in_batches(translator, to_translate)
        
    new_root = ET.Element('resources')
    
    def escape_apostrophes(text):
        return re.sub(r"(?<!\\)'", r"\'", text) if text else text

    for key, text in base_strings.items():
        final_text = translated_results.get(key, text)
        if key in skip_keys: final_text = text
        ET.SubElement(new_root, 'string', {'name': key}).text = escape_apostrophes(final_text)
        
    for key, items in base_plurals.items():
        pl_node = ET.SubElement(new_root, 'plurals', {'name': key})
        for qty, text in items.items():
            final_text = translated_results.get(f"{key}:{qty}", text)
            ET.SubElement(pl_node, 'item', {'quantity': qty}).text = escape_apostrophes(final_text)
            
    ET.indent(new_root, space="    ", level=0)
    with open(locale_path, 'w', encoding='utf-8') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n')
        f.write(ET.tostring(new_root, encoding='utf-8').decode('utf-8'))
    print(f"[{folder}] Finished successfully.", flush=True)

def main():
    res_dir = "core/resources/src/main/res"
    base_strings, base_plurals = load_xml(os.path.join(res_dir, "values", "strings.xml"))
    
    folders_to_process = [f for f in sorted(os.listdir(res_dir)) if f in LANG_MAP]
    print(f"Starting highly-robust parallel full translation for {len(folders_to_process)} locales...")
    
    with ThreadPoolExecutor(max_workers=6) as executor:
        futures = [executor.submit(process_locale, folder, res_dir, base_strings, base_plurals) for folder in folders_to_process]
        for future in as_completed(futures):
            try:
                future.result()
            except Exception as e:
                print(f"Error processing locale: {e}")
                traceback.print_exc()

if __name__ == "__main__":
    main()
