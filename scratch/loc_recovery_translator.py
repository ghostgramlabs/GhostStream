import os
import xml.etree.ElementTree as ET
import re
import time
from deep_translator import GoogleTranslator
from concurrent.futures import ThreadPoolExecutor, as_completed

LANG_MAP = {
    "values-af": "af", "values-ar": "ar", "values-de": "de", "values-el": "el",
    "values-es": "es", "values-es-rUS": "es", "values-fr": "fr", "values-hi": "hi",
    "values-id": "id", "values-in": "id", "values-it": "it", "values-ja": "ja",
    "values-ko": "ko", "values-ml": "ml", "values-nl": "nl", "values-pt": "pt",
    "values-pt-rBR": "pt", "values-ru": "ru", "values-sv": "sv", "values-ta": "ta",
    "values-te": "te", "values-th": "th", "values-tr": "tr", "values-vi": "vi",
    "values-zh-rCN": "zh-CN", "values-zh-rTW": "zh-TW"
}

def get_placeholders(text):
    if text is None: return []
    return [m.group(0) for m in re.finditer(r'%(\d+\$)?[-#+ 0,(\.<]*\d*(\.\d+)?[a-zA-Z%]', text) if m.group(0) != '%%']

def escape_apostrophes(text):
    return re.sub(r"(?<!\\)'", r"\'", text) if text else text

def load_xml(path):
    try:
        root = ET.parse(path).getroot()
        strings = {child.get('name'): child.text for child in root if child.tag == 'string'}
        plurals = {child.get('name'): {item.get('quantity'): item.text for item in child} for child in root if child.tag == 'plurals'}
        return strings, plurals
    except:
        return {}, {}

def translate_in_batches(translator, texts, batch_size=50):
    results = []
    for i in range(0, len(texts), batch_size):
        batch = texts[i:i+batch_size]
        try:
            translated_batch = translator.translate_batch(batch)
            results.extend(translated_batch)
        except Exception as e:
            print(f"Batch translation error: {e}")
            results.extend(batch)
        time.sleep(1)
    return [t.replace("GhostStream", "DirectServe").replace("GhostGram", "DirectServe") if t else t for t in results]

def process_locale(folder, res_dir, temp_dir, base_strings, base_plurals):
    skip_keys = ['common_separator_pipe', 'common_separator_dot', 'onboarding_page_counter', 'home_brand_title', 'splash_title', 'browser_title', 'dlna_server_name']
    
    locale_path = os.path.join(res_dir, folder, "strings.xml")
    old_path = os.path.join(temp_dir, folder, "strings.xml")
    
    translator = GoogleTranslator(source='en', target=LANG_MAP[folder])
    current_strings, current_plurals = load_xml(locale_path) if os.path.exists(locale_path) else ({}, {})
    old_strings, old_plurals = load_xml(old_path) if os.path.exists(old_path) else ({}, {})
    
    print(f"Processing {folder}...", flush=True)
    
    new_root = ET.Element('resources')
    
    to_translate = []
    translated_results = {}
    
    for key, text in base_strings.items():
        if not text: continue
        final_text = text
        if key in current_strings and current_strings[key] and current_strings[key] != text and sorted(get_placeholders(text)) == sorted(get_placeholders(current_strings[key])):
            final_text = current_strings[key]
        elif key in old_strings and old_strings[key] and old_strings[key] != text:
            old_val = old_strings[key].replace("GhostStream", "DirectServe").replace("GhostGram", "DirectServe")
            if sorted(get_placeholders(text)) == sorted(get_placeholders(old_val)):
                final_text = old_val
        
        if final_text == text and key not in skip_keys:
            to_translate.append((key, text, 'string', None))
            
    for key, items in base_plurals.items():
        for qty, text in items.items():
            if not text: continue
            final_text = text
            if key in current_plurals and qty in current_plurals[key] and current_plurals[key][qty] and current_plurals[key][qty] != text and sorted(get_placeholders(text)) == sorted(get_placeholders(current_plurals[key][qty])):
                final_text = current_plurals[key][qty]
            elif key in old_plurals and qty in old_plurals[key] and old_plurals[key][qty] and old_plurals[key][qty] != text:
                old_val = old_plurals[key][qty].replace("GhostStream", "DirectServe").replace("GhostGram", "DirectServe")
                if sorted(get_placeholders(text)) == sorted(get_placeholders(old_val)):
                    final_text = old_val
                    
            if final_text == text:
                to_translate.append((key, text, 'plural', qty))
                
    if to_translate:
        texts_to_trans = [item[1] for item in to_translate]
        print(f"[{folder}] Translating {len(texts_to_trans)} missing items...", flush=True)
        trans_results = translate_in_batches(translator, texts_to_trans)
        for i, res in enumerate(trans_results):
            item = to_translate[i]
            if res and sorted(get_placeholders(item[1])) == sorted(get_placeholders(res)):
                translated_results[f"{item[0]}:{item[3]}" if item[2] == 'plural' else item[0]] = res
    
    for key, text in base_strings.items():
        final_text = translated_results.get(key, text)
        if final_text == text:
            if key in current_strings and current_strings[key] and current_strings[key] != text and sorted(get_placeholders(text)) == sorted(get_placeholders(current_strings[key])): final_text = current_strings[key]
            elif key in old_strings and old_strings[key] and old_strings[key] != text:
                old_val = old_strings[key].replace("GhostStream", "DirectServe").replace("GhostGram", "DirectServe")
                if sorted(get_placeholders(text)) == sorted(get_placeholders(old_val)): final_text = old_val
        ET.SubElement(new_root, 'string', {'name': key}).text = escape_apostrophes(final_text)
        
    for key, items in base_plurals.items():
        pl_node = ET.SubElement(new_root, 'plurals', {'name': key})
        for qty, text in items.items():
            final_text = translated_results.get(f"{key}:{qty}", text)
            if final_text == text:
                if key in current_plurals and qty in current_plurals[key] and current_plurals[key][qty] and current_plurals[key][qty] != text and sorted(get_placeholders(text)) == sorted(get_placeholders(current_plurals[key][qty])): final_text = current_plurals[key][qty]
                elif key in old_plurals and qty in old_plurals[key] and old_plurals[key][qty] and old_plurals[key][qty] != text:
                    old_val = old_plurals[key][qty].replace("GhostStream", "DirectServe").replace("GhostGram", "DirectServe")
                    if sorted(get_placeholders(text)) == sorted(get_placeholders(old_val)): final_text = old_val
            ET.SubElement(pl_node, 'item', {'quantity': qty}).text = escape_apostrophes(final_text)
            
    ET.indent(new_root, space="    ", level=0)
    with open(locale_path, 'w', encoding='utf-8') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n')
        f.write(ET.tostring(new_root, encoding='utf-8').decode('utf-8'))
    print(f"Finished {folder}", flush=True)

def main():
    res_dir = "core/resources/src/main/res"
    temp_dir = "scratch/old_res"
    base_strings, base_plurals = load_xml(os.path.join(res_dir, "values", "strings.xml"))
    
    folders_to_process = [f for f in sorted(os.listdir(res_dir)) if f in LANG_MAP]
            
    print(f"Starting parallel processing for {len(folders_to_process)} locales...")
    with ThreadPoolExecutor(max_workers=10) as executor:
        futures = [executor.submit(process_locale, folder, res_dir, temp_dir, base_strings, base_plurals) for folder in folders_to_process]
        for future in as_completed(futures):
            try:
                future.result()
            except Exception as e:
                print(f"Error processing locale: {e}")

if __name__ == "__main__":
    main()
