###
# #%L
# Use nnInteractive in Fiji
# %%
# Copyright (C) 2026 DSCB
# %%
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as
# published by the Free Software Foundation, either version 2 of the
# License, or (at your option) any later version.
# 
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
# 
# You should have received a copy of the GNU General Public
# License along with this program.  If not, see
# <http://www.gnu.org/licenses/gpl-2.0.html>.
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
