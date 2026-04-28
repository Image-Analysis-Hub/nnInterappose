###
# #%L
# Use nnInteractive in Fiji
# %%
# Copyright (C) 2026 DSCB
# %%
# Redistribution and use in source and binary forms, with or without modification,
# are permitted provided that the following conditions are met:
# 
# 1. Redistributions of source code must retain the above copyright notice, this
#    list of conditions and the following disclaimer.
# 
# 2. Redistributions in binary form must reproduce the above copyright notice,
#    this list of conditions and the following disclaimer in the documentation
#    and/or other materials provided with the distribution.
# 
# 3. Neither the name of the DSCB nor the names of its contributors
#    may be used to endorse or promote products derived from this software without
#    specific prior written permission.
# 
# THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
# ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
# WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
# IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
# INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
# BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
# DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
# LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
# OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
# OF THE POSSIBILITY OF SUCH DAMAGE.
# #L%
###
import numpy as np
import torch
import os

def initialize_nn():
    """ Initialize nn-Interactive and return the nn session """
    from huggingface_hub import snapshot_download
    from nnInteractive.inference.inference_session import nnInteractiveInferenceSession

    REPO_ID = "nnInteractive/nnInteractive"
    MODEL_NAME = "nnInteractive_v1.0" 

    download_path = snapshot_download(
            repo_id=REPO_ID,
            allow_patterns=[f"{MODEL_NAME}/*"],
            force_download=False,
        )

    # --- Initialize Inference Session ---
    device = ( 
              torch.device("cuda") if torch.cuda.is_available()
    ## did not work on mps
    #else torch.device("mps") if torch.backends.mps.is_available()
    else torch.device("cpu")
    )
    session = nnInteractiveInferenceSession(
            device=device,  # Set inference device
            use_torch_compile=False,  # Experimental: Not tested yet
            verbose=False,
            torch_n_threads=int(os.cpu_count()*0.75),  # Use available CPU cores
            do_autozoom=True,  # Enables AutoZoom for better patching
            use_pinned_memory=True,  # Optimizes GPU memory transfers
        )

    # Load the trained model
    model_path = os.path.join(download_path, MODEL_NAME)
    session.initialize_from_trained_model_folder( model_path )
    return session
    
def set_image_nn( session, img ):
    """ Set the current image in the nnInteractive session """
    if len(img.shape) == 3:
        img = img[np.newaxis,...]  ## must be 4d in nnInter
    session.set_image(img) 
    shape = img.shape
    if len(shape) > 3:
        shape = shape[1:]
    target_tensor = torch.zeros( shape, dtype=torch.uint8 )
    session.set_target_buffer( target_tensor )
    return session, shape


appose_mode = 'task' in globals()
if not appose_mode:
   	from appose.python_worker import Task
   	task = Task()

task.update( message=f"Starting nnInteractive session" )
session = initialize_nn()

if appose_mode:
    img = image.ndarray()

task.update( message=f"Set image in nnInteractive" )
session, imgshape = set_image_nn( session, img )

task.update( message=f"Export nnsession" )
task.export( nnsession=session )
task.export( nnimgshape = imgshape )
