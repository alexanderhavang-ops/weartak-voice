#!/usr/bin/env python3
"""Feed the actual TAKS browser sender's PCM into the Java acoustic decoder."""
import argparse
import os
from pathlib import Path
import subprocess

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--taks-source',type=Path,required=True)
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
sender=args.taks_source.resolve()/'takctl/takctl/onboarding/soldier_card/watch_audio.js'
out=root/'core/build/browser-audio.pcm';out.parent.mkdir(parents=True,exist_ok=True)
script=r'''
const fs=require('fs'), {encode}=require(process.argv[1]);
const link='https://taklabs.example.org/api/onboarding/cards/watch-voice#'+'a'.repeat(43);
const signal=encode(link), pcm=Buffer.alloc(signal.length*2);
for(let i=0;i<signal.length;i++) pcm.writeInt16LE(Math.round(signal[i]*32767),i*2);
fs.writeFileSync(process.argv[2],pcm);
'''
subprocess.run(['node','-e',script,str(sender),str(out)],check=True)
gradle=os.environ.get('GRADLE_EXECUTABLE',str(root/'gradlew'))
raise SystemExit(subprocess.run([gradle,':core:test',f'-PaudioTestPcm={out}','--console=plain','--no-daemon'],cwd=root).returncode)
