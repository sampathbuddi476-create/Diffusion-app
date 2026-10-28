package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.AnimeCrimson
import com.example.ui.theme.DarkCharcoal
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceContainer
import com.example.ui.theme.DarkSurfaceContainerHigh
import com.example.ui.theme.DimText
import com.example.ui.theme.LightText
import com.example.ui.theme.MediumText
import com.example.ui.theme.OutlineDark
import com.example.ui.theme.StatusGreen

private const val CELL_1_CODE = """# Cell 1: Install packages for MeinaMix Generative AI & Cloudflared
!pip install -q fastapi "uvicorn[standard]" python-multipart diffusers transformers accelerate peft torchao opencv-python-headless torch torchvision
!wget -q -nc https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb
!dpkg -i cloudflared-linux-amd64.deb"""

private const val CELL_2_CODE = """%%writefile server.py
# Cell 2: MeinaMix Generative AI + LCM-LoRA Fast Video Stylization Backend
import os, tempfile, uuid, cv2, torch
from PIL import Image
import numpy as np
from fastapi import FastAPI, UploadFile, File, BackgroundTasks, HTTPException
from fastapi.responses import FileResponse
from diffusers import AutoPipelineForImage2Image, LCMScheduler

app = FastAPI(title="Titan Anime MeinaMix GPU Backend")

device = "cuda" if torch.cuda.is_available() else "cpu"
torch_dtype = torch.float16 if torch.cuda.is_available() else torch.float32

print("Loading MeinaMix Generative AI model onto GPU...")
# MeinaMix (anime diffusion) + Latent Consistency Model (LCM-LoRA) for ultra-fast 4-step inference
model_id = "Meina/MeinaMix_V11"
pipe = AutoPipelineForImage2Image.from_pretrained(
    model_id,
    torch_dtype=torch_dtype,
    safety_checker=None
).to(device)

# Load LCM-LoRA for fast video frames rendering (4-8 steps per frame)
pipe.scheduler = LCMScheduler.from_config(pipe.scheduler.config)
try:
    pipe.load_lora_weights("latent-consistency/lcm-lora-sdv1-5")
    pipe.fuse_lora()
except Exception as e:
    print(f"LCM-LoRA note: {e}")

pipe.enable_attention_slicing()

jobs = {}

def process_video_job(job_id: str, in_path: str, out_path: str):
    try:
        cap = cv2.VideoCapture(in_path)
        fps = cap.get(cv2.CAP_PROP_FPS) or 25
        w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        
        # Scale to max 512 for fast generative diffusion
        scale = min(512 / max(w, h), 1.0)
        target_w = int(w * scale) // 8 * 8
        target_h = int(h * scale) // 8 * 8
        
        fourcc = cv2.VideoWriter_fourcc(*'mp4v')
        out = cv2.VideoWriter(out_path, fourcc, fps, (target_w, target_h))
        
        prompt = "masterpiece, best quality, anime aesthetic, vibrant colors, makoto shinkai style, 8k, detailed anime eyes and hair"
        negative_prompt = "lowres, bad anatomy, bad hands, blurry, photo, realism, worst quality"
        
        while cap.isOpened():
            ret, frame = cap.read()
            if not ret: break
            
            frame_resized = cv2.resize(frame, (target_w, target_h))
            rgb_frame = cv2.cvtColor(frame_resized, cv2.COLOR_BGR2RGB)
            pil_img = Image.fromarray(rgb_frame)
            
            # 4-step fast diffusion with low strength to preserve video motion
            with torch.inference_mode():
                result_img = pipe(
                    prompt=prompt,
                    negative_prompt=negative_prompt,
                    image=pil_img,
                    num_inference_steps=4,
                    guidance_scale=1.5,
                    strength=0.45
                ).images[0]
                
            res_bgr = cv2.cvtColor(np.array(result_img), cv2.COLOR_RGB2BGR)
            out.write(res_bgr)
            
        cap.release()
        out.release()
        jobs[job_id]["status"] = "completed"
        jobs[job_id]["out_path"] = out_path
    except Exception as e:
        jobs[job_id]["status"] = "failed"
        jobs[job_id]["message"] = str(e)

@app.get("/")
def health_check():
    gpu_name = torch.cuda.get_device_name(0) if torch.cuda.is_available() else "CPU"
    return {"status": "online", "model": "MeinaMix V11 (LCM-LoRA)", "gpu": gpu_name}

@app.post("/stylize/start")
async def start_stylize(background_tasks: BackgroundTasks, video: UploadFile = File(...)):
    job_id = str(uuid.uuid4())
    in_path = tempfile.mktemp(suffix=".mp4")
    out_path = tempfile.mktemp(suffix=".mp4")
    with open(in_path, "wb") as f:
        f.write(await video.read())
        
    jobs[job_id] = {"status": "processing", "in_path": in_path, "out_path": out_path}
    background_tasks.add_task(process_video_job, job_id, in_path, out_path)
    return {"job_id": job_id, "status": "processing"}

@app.get("/stylize/status/{job_id}")
def check_status(job_id: str):
    if job_id not in jobs:
        raise HTTPException(status_code=404, detail="Job not found")
    job = jobs[job_id]
    return {"job_id": job_id, "status": job["status"], "message": job.get("message")}

@app.get("/stylize/download/{job_id}")
def download_result(job_id: str):
    if job_id not in jobs:
        raise HTTPException(status_code=404, detail="Job not found")
    job = jobs[job_id]
    if job["status"] != "completed" or not os.path.exists(job.get("out_path", "")):
        raise HTTPException(status_code=400, detail="Job is not completed yet")
    return FileResponse(job["out_path"], media_type="video/mp4", filename=f"anime_{job_id}.mp4")"""

private const val CELL_3_CODE = """# Cell 3: Launch server (waiting for 2GB MeinaMix model download) & Open Tunnel
import subprocess, time, re, urllib.request

# 1. Start uvicorn server in background
print("Starting FastAPI backend... Loading 2GB MeinaMix weights onto GPU (takes 2-4 minutes on first run)...")
server_proc = subprocess.Popen(["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8000"])

# 2. Loop up to 120 times (waiting for model download & server ready)
server_ready = False
for attempt in range(120):
    try:
        with urllib.request.urlopen("http://localhost:8000/", timeout=2) as response:
            if response.status == 200:
                print(f"\\n[OK] MeinaMix model ready and backend online! (took ~{attempt * 3}s)")
                server_ready = True
                break
    except Exception:
        pass
    print(".", end="", flush=True)
    time.sleep(3)

if not server_ready:
    print("\\nWarning: Server is still initializing. Proceeding to open tunnel...")

# 3. Start Cloudflare tunnel
print("\\nOpening Cloudflare tunnel...")
tunnel_proc = subprocess.Popen(
    ["cloudflared", "tunnel", "--url", "http://localhost:8000"],
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    universal_newlines=True
)

for line in tunnel_proc.stdout:
    match = re.search(r"https://[a-zA-Z0-9-]+\.trycloudflare\.com", line)
    if match:
        url = match.group(0)
        print("\\n" + "="*55)
        print(">> YOUR TITAN ANIME SERVER URL:")
        print(url)
        print("="*55 + "\\n")
        print("Copy the URL above and paste it into Titan Anime app!")
        break"""

@Composable
fun ColabHelpDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(max = 700.dp)
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, OutlineDark, RoundedCornerShape(24.dp))
                .testTag("colab_help_dialog"),
            color = DarkSurface,
            contentColor = LightText
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AnimeCrimson.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = AnimeCrimson,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "MeinaMix Generative AI Backend",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = LightText
                                )
                            )
                            Text(
                                text = "Google Colab T4 GPU Setup Guide",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = DimText,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = DimText
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Scrollable content body
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollState)
                ) {
                    // Open Google Colab Action Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceContainer),
                        border = CardDefaults.outlinedCardBorder().copy(
                            brush = androidx.compose.ui.graphics.SolidColor(AnimeCrimson.copy(alpha = 0.4f))
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Ready to start your free GPU?",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        color = LightText
                                    )
                                )
                                Text(
                                    text = "Launch a notebook on Google Colab with free T4 GPU",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = DimText,
                                        fontSize = 11.sp
                                    )
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    val intent = Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://colab.research.google.com")
                                    )
                                    context.startActivity(intent)
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AnimeCrimson,
                                    contentColor = Color.White
                                )
                            ) {
                                Text("Open Colab", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Launch,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Model Weight Download Warning Note
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF332014))
                            .border(1.dp, Color(0xFFCC7A00).copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = "Important Note",
                                tint = Color(0xFFFFB300),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "First-Run Model Download (2GB)",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFFCC80)
                                    )
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "The backend utilizes the advanced MeinaMix Generative AI model with LCM-LoRA. The first run in Colab takes 2–4 minutes to automatically download the 2GB weights before the server starts.",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = Color(0xFFFFE0B2),
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp
                                    )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Step 1 Card
                    StepHeader(
                        stepNumber = "1",
                        icon = Icons.Default.Memory,
                        title = "Select T4 GPU in Colab",
                        description = "In your Colab notebook menu, click: Runtime → Change runtime type → Hardware accelerator: Select 'T4 GPU' → Save."
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Step 2 Card & Code snippets
                    StepHeader(
                        stepNumber = "2",
                        icon = Icons.Default.Terminal,
                        title = "Run Setup Cells",
                        description = "Create 3 code cells in your notebook and run them in sequence:"
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Cell 1
                    CodeBlockCard(
                        title = "Cell 1: Dependencies & Cloudflared",
                        code = CELL_1_CODE,
                        onCopy = { copyToClipboard(context, CELL_1_CODE, "Cell 1 copied!") }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Cell 2
                    CodeBlockCard(
                        title = "Cell 2: MeinaMix Generative AI Server (server.py)",
                        code = CELL_2_CODE,
                        onCopy = { copyToClipboard(context, CELL_2_CODE, "Cell 2 copied!") }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Cell 3
                    CodeBlockCard(
                        title = "Cell 3: Launch Server (2GB model wait) & Open Tunnel",
                        code = CELL_3_CODE,
                        onCopy = { copyToClipboard(context, CELL_3_CODE, "Cell 3 copied!") }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Step 3 Card
                    StepHeader(
                        stepNumber = "3",
                        icon = Icons.Default.Dns,
                        title = "Copy Public Tunnel URL into App",
                        description = "Once Cell 3 prints '>> YOUR TITAN ANIME SERVER URL:', copy the URL ending in .trycloudflare.com and paste it into the Server URL card at the top of Titan Anime!"
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Footer Row with tactile button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.5.dp, AnimeCrimson),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = AnimeCrimson.copy(alpha = 0.1f),
                            contentColor = AnimeCrimson
                        ),
                        modifier = Modifier
                            .height(44.dp)
                            .testTag("dialog_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Close Guide",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepHeader(
    stepNumber: String,
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(AnimeCrimson),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stepNumber,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = LightText
                )
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MediumText,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            )
        }
    }
}

@Composable
private fun CodeBlockCard(
    title: String,
    code: String,
    onCopy: () -> Unit
) {
    var copied by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCharcoal),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(OutlineDark)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = LightText,
                        fontSize = 11.sp
                    )
                )

                OutlinedButton(
                    onClick = {
                        onCopy()
                        copied = true
                    },
                    modifier = Modifier.height(28.dp),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(
                            if (copied) StatusGreen else AnimeCrimson.copy(alpha = 0.6f)
                        )
                    )
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        tint = if (copied) StatusGreen else AnimeCrimson,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (copied) "Copied" else "Copy",
                        fontSize = 10.sp,
                        color = if (copied) StatusGreen else AnimeCrimson,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Monospace Code Container with Horizontal Scrolling
            val codeScrollState = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(codeScrollState)
                    .padding(12.dp)
            ) {
                Text(
                    text = code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = Color(0xFFE0E0E0)
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String, message: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = ClipData.newPlainText("Colab Code", text)
    clipboard?.setPrimaryClip(clip)
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
