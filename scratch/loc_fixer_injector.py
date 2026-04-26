import sys

NEW_STRINGS = {
    'home_section_library': {
        'es': 'Biblioteca y transferencias',
        'ar': 'المكتبة والتحويلات',
        'hi': 'लाइब्रेरी और ट्रांसफर',
        'ru': 'Библиотека и передачи',
        'ja': 'ライブラリと転送',
        'zh-rCN': '库与传输',
        'zh-rTW': '媒體庫與傳輸',
        'ko': '라이브러리 및 전송',
        'nl': 'Bibliotheek & overdrachten',
        'sv': 'Bibliotek & överföringar',
        'el': 'Βιβλιοθήκη & μεταφορές',
        'tr': 'Kütüphane ve transferler',
        'id': 'Perpustakaan & transfer',
        'in': 'Perpustakaan & transfer',
        'vi': 'Thư viện & truyền tải',
        'th': 'คลังและรายการโอน',
        'af': 'Biblioteek & oordragte',
        'ta': 'நூலகம் மற்றும் இடமாற்றங்கள்',
        'te': 'లైబ్రరీ & బదిలీలు',
        'ml': 'ലൈബ്രറിയും കൈമാറ്റങ്ങളും',
        'fr': 'Bibliothèque et transferts',
        'de': 'Bibliothek & Transfers',
        'it': 'Libreria e trasferimenti',
        'pt': 'Biblioteca e transferências',
        'pt-rBR': 'Biblioteca e transferências'
    },
    'home_section_live_tools': {
        'es': 'Herramientas en vivo',
        'ar': 'أدوات البث المباشر',
        'hi': 'लाइव टूल्स',
        'ru': 'Инструменты в реальном времени',
        'ja': 'ライブツール',
        'zh-rCN': '直播工具',
        'zh-rTW': '即時工具',
        'ko': '라이브 도구',
        'nl': 'Live hulpprogramma\'s',
        'sv': 'Liveverktyg',
        'el': 'Εργαλεία Live',
        'tr': 'Canlı araçlar',
        'id': 'Alat siaran langsung',
        'in': 'Alat siaran langsung',
        'vi': 'Công cụ trực tiếp',
        'th': 'เครื่องมือสด',
        'af': 'Live-gereedskap',
        'ta': 'நேரடி கருவிகள்',
        'te': 'లైవ్ టూల్స్',
        'ml': 'തത്സമയ ഉപകരണങ്ങൾ',
        'fr': 'Outils en direct',
        'de': 'Live-Tools',
        'it': 'Strumenti live',
        'pt': 'Ferramentas ao vivo',
        'pt-rBR': 'Ferramentas ao vivo'
    },
    # ... I will add more here
}

# Actually, I will just write a script that takes a dictionary of translations and injects them.
# But generating the whole dictionary here is better.

def inject():
    # This is too complex for a single turn if I include all 60 strings x 25 languages.
    # I'll do it in a more automated way.
    pass

if __name__ == "__main__":
    inject()
