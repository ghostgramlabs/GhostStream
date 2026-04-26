import os
import xml.etree.ElementTree as ET

def rebuild_locale(res_dir, locale_name, translation_dict):
    base_path = os.path.join(res_dir, "values", "strings.xml")
    target_dir = os.path.join(res_dir, f"values-{locale_name}")
    if not os.path.exists(target_dir):
        os.makedirs(target_dir)
        
    target_path = os.path.join(target_dir, "strings.xml")
    tree = ET.parse(base_path)
    root = tree.getroot()
    new_root = ET.Element('resources')
    
    for child in root:
        if child.tag == 'string':
            name = child.get('name')
            translatable = child.get('translatable', 'true')
            new_node = ET.SubElement(new_root, 'string', {'name': name})
            if translatable == 'false':
                new_node.set('translatable', 'false')
                new_node.text = child.text
            else:
                val = translation_dict.get(name)
                new_node.text = val if val else child.text
        elif child.tag == 'plurals':
            name = child.get('name')
            new_node = ET.SubElement(new_root, 'plurals', {'name': name})
            plural_data = translation_dict.get(f"plural:{name}", {})
            for item in child:
                qty = item.get('quantity')
                new_item = ET.SubElement(new_node, 'item', {'quantity': qty})
                val = plural_data.get(qty)
                new_item.text = val if val else item.text

    ET.indent(new_root, space="    ", level=0)
    rough_string = ET.tostring(new_root, encoding='utf-8').decode('utf-8')
    with open(target_path, 'w', encoding='utf-8') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n')
        f.write(rough_string)

# Apply batch
res_dir = "core/resources/src/main/res"
rebuild_locale(res_dir, "af", {})
