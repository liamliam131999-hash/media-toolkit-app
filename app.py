import os
import subprocess
import tempfile
import streamlit as st
from groq import Groq

st.set_page_config(
    page_title="Video & Audio Toolbox", page_icon="🎬", layout="centered"
)

st.title("🎬 Video & Audio Toolbox (Groq SRT & Compressor)")
st.write(
    "Groq Whisper ကိုသုံးပြီး Timestamp SRT ဖိုင်ထုတ်ခြင်း နဲ့ Video/Audio"
    " ဖိုင်ဆိုဒ်ချုံ့ခြင်းတို့ကို တစ်နေရာတည်းမှာ လုပ်ဆောင်နိုင်ပါတယ်။"
)

# Sidebar - API Key ထည့်ရန်
st.sidebar.header("🔑 Settings")
groq_api_key = st.sidebar.text_input(
    "Groq API Key ထည့်ပါ", type="password"
)
st.sidebar.markdown(
    "[Groq Console](https://console.groq.com) မှ API Key အခမဲ့ ယူနိုင်ပါသည်။"
)

# Tabs ခွဲခြင်း
tab1, tab2 = st.tabs(["📝 Timestamp SRT Generator", "📉 File Size Compressor"])

# --- TAB 1: SRT GENERATOR ---
with tab1:
  st.header("Timestamp SRT ဖိုင်ထုတ်ရန်")
  uploaded_audio = st.file_uploader(
      "Audio သို့မဟုတ် Video ဖိုင်ကို တင်ပါ (.mp3, .mp4, .wav, .m4a)",
      type=["mp3", "mp4", "wav", "m4a", "mpeg"],
      key="srt_file",
  )

  if uploaded_audio is not None:
    st.audio(uploaded_audio)

    if st.button("🚀 SRT ဖိုင် စတင်ထုတ်မည်"):
      if not groq_api_key:
        st.error("ကျေးဇူးပြု၍ Sidebar တွင် Groq API Key ထည့်ပါ။")
      else:
        with st.spinner(
            "🔄 Groq API သို့ ပေးပို့နေပါပြီ (ခဏစောင့်ပါ)..."
        ):
          try:
            with tempfile.NamedTemporaryFile(
                delete=False, suffix=os.path.splitext(uploaded_audio.name)[1]
            ) as tmp_file:
              tmp_file.write(uploaded_audio.read())
              tmp_path = tmp_file.name

            client = Groq(api_key=groq_api_key)

            with open(tmp_path, "rb") as file:
              transcription = client.audio.transcriptions.create(
                  file=(os.path.basename(tmp_path), file.read()),
                  model="whisper-large-v3-turbo",
                  response_format="verbose_json",
                  timestamp_granularities=["segment"],
              )

            srt_content = ""


            def format_time(seconds):
              hours = int(seconds // 3600)
              minutes = int((seconds % 3600) // 60)
              secs = int(seconds % 60)
              milliseconds = int((seconds - int(seconds)) * 1000)
              return f"{hours:02d}:{minutes:02d}:{secs:02d},{milliseconds:03d}"

            for i, segment in enumerate(transcription.segments, start=1):
              start_str = format_time(segment["start"])
              end_str = format_time(segment["end"])
              text = segment["text"].strip()
              srt_content += (
                  f"{i}\n{start_str} --> {end_str}\n{text}\n\n"
              )

            os.unlink(tmp_path)

            st.success("✅ SRT ဖိုင် အောင်မြင်စွာ ထွက်လာပါပြီ!")
            st.download_button(
                label="📥 Download SRT File",
                data=srt_content,
                file_name="output.srt",
                mime="text/plain",
            )
          except Exception as e:
            st.error(f"Error ဖြစ်ပေါ်သည်: {e}")

# --- TAB 2: VIDEO COMPRESSOR ---
with tab2:
  st.header("Video ဖိုင်ဆိုဒ် ချုံ့ရန်")
  uploaded_video = st.file_uploader(
      "ဗီဒီယိုဖိုင်ကို တင်ပါ (.mp4, .mov, .avi)",
      type=["mp4", "mov", "avi", "mkv"],
      key="comp_file",
  )

  crf_option = st.slider(
      "Compression ပမာဏ (CRF) - များလေ ဖိုင်ဆိုဒ်သေးလေ",
      min_value=20,
      max_value=35,
      value=28,
  )

  if uploaded_video is not None:
    if st.button("📉 ဖိုင်ဆိုဒ် စတင်ချုံ့မည်"):
      with st.spinner("📉 ဗီဒီယိုကို ချုံ့နေပါပြီ (ခဏစောင့်ပါ)..."):
        try:
          with tempfile.NamedTemporaryFile(
              delete=False, suffix=".mp4"
          ) as input_tmp:
            input_tmp.write(uploaded_video.read())
            input_path = input_tmp.name

          output_path = input_path.replace(".mp4", "_compressed.mp4")

          cmd = [
              "ffmpeg",
              "-i",
              input_path,
              "-vcodec",
              "libx264",
              "-crf",
              str(crf_option),
              output_path,
          ]
          subprocess.run(cmd, check=True)

          st.success("✅ ဖိုင်ဆိုဒ်ချုံ့ခြင်း ပြီးစီးပါပြီ!")

          with open(output_path, "rb") as f:
            st.download_button(
                label="📥 Download Compressed Video",
                data=f,
                file_name="compressed_video.mp4",
                mime="video/mp4",
            )

          os.unlink(input_path)
          os.unlink(output_path)
        except Exception as e:
          st.error(f"Error ဖြစ်ပေါ်သည်: {e}")
