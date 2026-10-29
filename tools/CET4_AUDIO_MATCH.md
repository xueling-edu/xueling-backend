# 四级词发音匹配

`prepare_cet4_audio.py` 从本地 ECDICT 生成四级词清单，再从 Kaikki 英文词典匹配美音录音的 URL。维护者已确认本地与线上四级词书一致。输出保存在被 Git 忽略的 `data/` 目录；音频文件不进入仓库。

```bash
python tools/prepare_cet4_audio.py candidates --ecdict data/ecdict.csv --output data/cet4_audio_candidates.csv
python tools/prepare_cet4_audio.py fetch --words data/cet4_audio_candidates.csv --output data/kaikki_cet4_all.jsonl --errors data/kaikki_cet4_final_errors.csv --limit 0 --resume
python tools/prepare_cet4_audio.py match --words data/cet4_audio_candidates.csv --kaikki data/kaikki_cet4_all.jsonl --errors data/kaikki_cet4_final_errors.csv --output data/cet4_audio_all_matches.csv
```

`fetch` 读取 [Kaikki 英文词典](https://kaikki.org/dictionary/English/)的公开词条页面，只缓存词形和 `sounds` 字段。`--resume` 会跳过已抓取的词。`match` 输出单一美音匹配、多录音待审核、无美音匹配和源页面缺失四类结果。也可用 `--kaikki` 指向完整英文 JSONL 或 `.jsonl.gz` 数据包。

2026-10-29 的结果：3,849 个四级词中，3,019 个有单一美音录音，187 个有多条录音待审核，637 个未匹配到美音，6 个源页面未找到。源页面未找到不等于该词没有录音。前端通过 `scripts/build-cet4-audio-manifest.py` 只将 3,019 个单一匹配词的 MP3 URL 编入播放清单；其余词使用浏览器语音。

匹配结果只是音源元数据。对外发布前仍需核对实际发音、逐文件许可证和署名要求。
