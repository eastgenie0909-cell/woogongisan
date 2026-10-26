"""Download official build tools locally; SDK package installation is separate."""
from pathlib import Path
import urllib.request,xml.etree.ElementTree as ET,zipfile,concurrent.futures
ROOT=Path(__file__).parent/'.tools';ROOT.mkdir(exist_ok=True)
xml=urllib.request.urlopen('https://dl.google.com/android/repository/repository2-1.xml',timeout=60).read();tree=ET.fromstring(xml)
url=None
for pkg in tree.iter('remotePackage'):
 if pkg.attrib.get('path')=='cmdline-tools;latest':
  for archive in pkg.iter('archive'):
   if archive.findtext('host-os')=='windows':url='https://dl.google.com/android/repository/'+archive.findtext('complete/url')
assert url
items=[('jdk.zip','https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk'),('sdk.zip',url)]
def fetch(item):
 name,url=item;p=ROOT/name
 if not p.exists():urllib.request.urlretrieve(url,p)
 dest=ROOT/('jdk' if name=='jdk.zip' else 'sdk/cmdline-tools')
 if not dest.exists():
  dest.mkdir(parents=True)
  with zipfile.ZipFile(p) as z:z.extractall(dest)
 return name,p.stat().st_size
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as ex:
 for result in ex.map(fetch,items):print(result,flush=True)
print('downloads ready',flush=True)
