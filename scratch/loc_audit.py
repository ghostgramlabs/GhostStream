import os
import xml.etree.ElementTree as ET
import re
import sys

def get_placeholders(text):
    if text is None:
        return []
    # Match %s, %d, %f, %1$s, %2$d, %.1f, %02d, %% etc.
    # Pattern: %[argument_index$][flags][width][.precision]conversion
    pattern = r'%(\d+\$)?[-#+ 0,(\.<]*\d*(\.\d+)?[a-zA-Z%]'
    placeholders = []
    for m in re.finditer(pattern, text):
        ph = m.group(0)
        # %% is a literal %, don't count as a formatting placeholder for comparison
        if ph != '%%':
            placeholders.append(ph)
    return placeholders

def audit_locale(base_strings, locale_path, locale_name):
    print(f"\nLocale: {locale_name}")
    errors = 0
    warnings = 0
    
    try:
        tree = ET.parse(locale_path)
        root = tree.getroot()
    except Exception as e:
        print(f"  [CRITICAL] Invalid XML: {e}")
        return 1, 0

    locale_strings = {}
    locale_plurals = {}
    
    for child in root:
        if child.tag == 'string':
            name = child.get('name')
            if name in locale_strings:
                print(f"  [ERROR] Duplicate key: {name}")
                errors += 1
            locale_strings[name] = child.text
        elif child.tag == 'plurals':
            name = child.get('name')
            items = {}
            for item in child:
                items[item.get('quantity')] = item.text
            locale_plurals[name] = items

    missing_keys = []
    extra_keys = []
    placeholder_mismatches = []
    empty_translations = []
    same_as_english = []
    
    # Check strings
    base_string_keys = base_strings['strings'].keys()
    for key in base_string_keys:
        # Check if translatable=false
        # In base_strings we should store the 'translatable' attribute too
        if base_strings['translatable'].get(key) == 'false':
            continue

        if key not in locale_strings:
            missing_keys.append(key)
        else:
            base_val = base_strings['strings'][key]
            loc_val = locale_strings[key]
            
            if loc_val is None or loc_val.strip() == "":
                empty_translations.append(key)
            elif loc_val == base_val:
                # Brands and specific terms are okay
                if key not in ['common_separator_pipe', 'common_separator_dot', 'onboarding_page_counter', 'home_brand_title', 'splash_title', 'browser_title', 'dlna_server_name']:
                    same_as_english.append(key)
            
            base_ph = get_placeholders(base_val)
            loc_ph = get_placeholders(loc_val)
            # Use sets or sorted lists to compare
            if sorted(base_ph) != sorted(loc_ph):
                placeholder_mismatches.append((key, base_ph, loc_ph))

    for key in locale_strings:
        if key not in base_string_keys:
            extra_keys.append(key)

    # Check plurals
    base_plural_keys = base_strings['plurals'].keys()
    for key in base_plural_keys:
        if key not in locale_plurals:
            missing_keys.append(f"plural:{key}")
        else:
            base_items = base_strings['plurals'][key]
            loc_items = locale_plurals[key]
            
            for qty, val in loc_items.items():
                loc_ph = get_placeholders(val)
                # Compare against the same quantity in English, or fallback to 'other'
                base_qty_val = base_items.get(qty, base_items.get('other', ''))
                base_qty_ph = get_placeholders(base_qty_val)
                
                if sorted(base_qty_ph) != sorted(loc_ph):
                     placeholder_mismatches.append((f"{key}:{qty}", base_qty_ph, loc_ph))

    for key in locale_plurals:
        if key not in base_plural_keys:
            extra_keys.append(f"plural:{key}")

    if missing_keys:
        print(f"  Missing keys: {len(missing_keys)}")
        errors += len(missing_keys)
    if extra_keys:
        print(f"  Extra keys: {len(extra_keys)}")
        # Extra keys are often removed in cleanup, let's count as error for strict alignment
        errors += len(extra_keys)
    if placeholder_mismatches:
        print(f"  Placeholder mismatches: {len(placeholder_mismatches)}")
        errors += len(placeholder_mismatches)
        for key, b, l in placeholder_mismatches:
            print(f"    [MISMATCH] {key}: English={b}, Locale={l}")
    if empty_translations:
        print(f"  Empty translations: {len(empty_translations)}")
        errors += len(empty_translations)
    if same_as_english:
        print(f"  Same-as-English suspicious: {len(same_as_english)}")
        warnings += len(same_as_english)

    if errors == 0:
        print("  Status: OK")
    
    return errors, warnings

def load_base(base_path):
    tree = ET.parse(base_path)
    root = tree.getroot()
    strings = {}
    plurals = {}
    translatable = {}
    for child in root:
        if child.tag == 'string':
            name = child.get('name')
            strings[name] = child.text
            translatable[name] = child.get('translatable', 'true')
        elif child.tag == 'plurals':
            name = child.get('name')
            items = {}
            for item in child:
                items[item.get('quantity')] = item.text
            plurals[name] = items
            translatable[name] = child.get('translatable', 'true')
    return {'strings': strings, 'plurals': plurals, 'translatable': translatable}

if __name__ == "__main__":
    res_dir = "core/resources/src/main/res"
    base_path = os.path.join(res_dir, "values", "strings.xml")
    if not os.path.exists(base_path):
        print(f"Base strings not found at {base_path}")
        sys.exit(1)
        
    base_strings = load_base(base_path)
    print(f"Loaded {len(base_strings['strings'])} base strings and {len(base_strings['plurals'])} plurals.")
    
    total_errors = 0
    total_warnings = 0
    locales_count = 0
    
    for folder in sorted(os.listdir(res_dir)):
        if folder.startswith("values-"):
            locale_path = os.path.join(res_dir, folder, "strings.xml")
            if os.path.exists(locale_path):
                locales_count += 1
                e, w = audit_locale(base_strings, locale_path, folder)
                total_errors += e
                total_warnings += w
    
    print("\n" + "="*40)
    print("FINAL REPORT")
    print("="*40)
    print(f"Locales processed: {locales_count}")
    print(f"Total Errors: {total_errors}")
    print(f"Total Warnings: {total_warnings}")
    
    if total_errors > 0:
        print("\n[AUDIT FAILED] Cleanup and translation required.")
        sys.exit(1)
    else:
        print("\n[AUDIT PASSED] All locales are technically aligned.")
        sys.exit(0)
