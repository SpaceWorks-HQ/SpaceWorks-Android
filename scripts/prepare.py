"""Generate native semantic colors and Android vector icon from brand.json inputs."""
from pathlib import Path
import json,xml.etree.ElementTree as ET,re
root=Path(__file__).resolve().parents[1]
brand=json.loads((root/'branding/brand.json').read_text(encoding='utf-8'))
tokens=json.loads((root/brand['theme_tokens']).read_text(encoding='utf-8'))
out=root/'app/build/generated/source/brand/org/spaceworks/app/theme';out.mkdir(parents=True,exist_ok=True)
lines=['package org.spaceworks.app.theme','', 'import androidx.compose.ui.graphics.Color','import androidx.compose.material3.*','', 'object BrandTokens {']
for mode in ['light','dark']:
    lines+=['    object '+mode.title()+' {']
    for name,value in tokens[mode].items():
        key=re.sub(r'-([a-z])',lambda m:m[1].upper(),name)
        lines+=['        val '+key+' = Color(0xFF'+value.lstrip('#')+')']
    lines+=['    }']
lines+=['}']
for mode in ['light','dark']:
    t='BrandTokens.'+mode.title()+'.'
    pairs={'primary':'accent','onPrimary':'onAccent','primaryContainer':'accentSoft','onPrimaryContainer':'accentInk','secondary':'emphasis','onSecondary':'onEmphasis','secondaryContainer':'emphasisSoft','onSecondaryContainer':'ink','tertiary':'info','onTertiary':'onInfo','background':'bg','onBackground':'ink','surface':'surface','onSurface':'ink','surfaceVariant':'panel','onSurfaceVariant':'muted','outline':'outline','outlineVariant':'line','error':'danger','onError':'onDanger','errorContainer':'danger','onErrorContainer':'onDanger','inverseSurface':'ink','inverseOnSurface':'bg','inversePrimary':'accentInk','surfaceTint':'accent','tertiaryContainer':'infoSoft','onTertiaryContainer':'infoInk','surfaceDim':'surface','surfaceBright':'panel','surfaceContainerLowest':'bg','surfaceContainerLow':'surface','surfaceContainer':'panel','surfaceContainerHigh':'panel','surfaceContainerHighest':'hover','scrim':'ink'}
    lines+=['val '+mode.title()+'Scheme = '+mode+'ColorScheme('+', '.join(k+' = '+t+v for k,v in pairs.items())+')']
(out/'BrandTokens.kt').write_text('\n'.join(lines)+'\n')
svg=ET.parse(root/brand['app_icon']).getroot()
view=svg.attrib.get('viewBox','0 0 512 512').split()
vector=ET.Element('vector',{'xmlns:android':'http://schemas.android.com/apk/res/android','android:width':'108dp','android:height':'108dp','android:viewportWidth':view[2],'android:viewportHeight':view[3]})
for node in svg.iter():
    tag=node.tag.split('}')[-1]
    if tag=='rect':
        x=float(node.get('x','0'));y=float(node.get('y','0'));ww=float(node.get('width'));hh=float(node.get('height'))
        r=min(float(node.get('rx','0')),ww/2,hh/2)
        d=f'M{x+r},{y}h{ww-2*r}a{r},{r} 0 0 1 {r},{r}v{hh-2*r}a{r},{r} 0 0 1 {-r},{r}h{-ww+2*r}a{r},{r} 0 0 1 {-r},{-r}v{-hh+2*r}a{r},{r} 0 0 1 {r},{-r}z' if r else f'M{x},{y}h{ww}v{hh}h-{ww}z'
    elif tag=='path':d=node.get('d')
    else:continue
    ET.SubElement(vector,'path',{'android:fillColor':node.get('fill','#142C2A'),'android:pathData':d})
res=root/'app/build/generated/res/brand/drawable';res.mkdir(parents=True,exist_ok=True)
ET.ElementTree(vector).write(res/'ic_brand.xml',encoding='utf-8',xml_declaration=True)
