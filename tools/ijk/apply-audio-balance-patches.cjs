// LGPL-2.1-or-later. Measure decoded S16 audio before the player's volume gain.
const fs=require('fs'),path=require('path');
function apply(root){
  const header=path.join(root,'ijkmedia/ijkplayer/ff_ffplay_def.h');
  let h=fs.readFileSync(header,'utf8');
  // AVOptions requires av_class to remain the first member. Append our fields.
  h=h.replace(/\n    int ntv_audio_measure;\n    int ntv_audio_power;/g,'');
  if(!h.includes('} FFPlayer;'))throw Error('Missing FFPlayer layout');
  h=h.replace('} FFPlayer;','    int ntv_audio_measure;\n    int ntv_audio_power;\n} FFPlayer;');
  fs.writeFileSync(header,h);
  const file=path.join(root,'ijkmedia/ijkplayer/ff_ffplay.c');let s=fs.readFileSync(file,'utf8');
  if(s.includes('/* nTv decoded audio power'))return;
  const anchor='        if (!is->muted && is->audio_buf && is->audio_volume == SDL_MIX_MAXVOLUME)';
  if(!s.includes(anchor))throw Error('Missing audio output anchor');
  s=s.replace(anchor,`        /* nTv decoded audio power: no allocation, at most 256 samples per chunk. */
        if (__atomic_load_n(&ffp->ntv_audio_measure, __ATOMIC_RELAXED) && is->audio_buf && len1 >= 2) {
            const int16_t *pcm = (const int16_t *)(is->audio_buf + is->audio_buf_index);
            int samples = len1 / 2, stride = (samples + 255) / 256, count = 0;
            int64_t sum = 0;
            for (int i = 0; i < samples; i += stride) { int64_t v = pcm[i]; sum += v * v; ++count; }
            __atomic_store_n(&ffp->ntv_audio_power, (int)(sum / count), __ATOMIC_RELAXED);
        }
${anchor}`);
  const get='        case FFP_PROP_INT64_SELECTED_VIDEO_STREAM:';
  s=s.replace(get,`        case 22017: return __atomic_load_n(&ffp->ntv_audio_power, __ATOMIC_RELAXED);
${get}`);
  const start=s.indexOf('void ffp_set_property_int64('),part=s.slice(start);
  const match=part.match(/switch\s*\(id\)\s*\{/);if(!match)throw Error('Missing property setter');
  s=s.slice(0,start)+part.replace(match[0],match[0]+`\n        case 22017:
            __atomic_store_n(&ffp->ntv_audio_measure, value != 0, __ATOMIC_RELAXED);
            if (!value) __atomic_store_n(&ffp->ntv_audio_power, 0, __ATOMIC_RELAXED);
            break;`);
  fs.writeFileSync(file,s);
}
module.exports=apply;
if(require.main===module)apply(path.resolve(process.argv[2]||'.codex-tmp/ijkplayer-0.8.8'));
