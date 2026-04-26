import xml.etree.ElementTree as ET
import json
import os

res_dir = "core/resources/src/main/res"
base_path = os.path.join(res_dir, "values", "strings.xml")

tree = ET.parse(base_path)
root = tree.getroot()

keys = {}
for child in root:
    if child.tag == 'string':
        keys[child.get('name')] = child.text
    elif child.tag == 'plurals':
        name = child.get('name')
        items = {}
        for item in child:
            items[item.get('quantity')] = item.text
        keys[f"plural:{name}"] = items
    elif child.tag == 'string-array':
        name = child.get('name')
        items = [item.text for item in child]
        keys[f"array:{name}"] = items

with open("scratch/english_template.json", "w", encoding="utf-8") as f:
    json.dump(keys, f, indent=2, ensure_ascii=False)

print("Exported English template to scratch/english_template.json")
