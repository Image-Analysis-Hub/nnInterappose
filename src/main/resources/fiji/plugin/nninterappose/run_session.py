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
task.update( message=f"nnInteractive segmentation.." )

from skimage.measure import find_contours
from skimage.morphology import dilation, disk

def get_contours_from_zstack( binary_stack ):
    """
    Extract contour coordinates from a binary z-stack.
    """
    contours = {}
    for z in range(binary_stack.shape[0]):
        slice_2d = binary_stack[z]
        
        if not slice_2d.any():
            continue  # no object in this slice
        
        # find_contours returns list of (N, 2) arrays with (row, col) coords
        found = find_contours(slice_2d, level=0.5)
        
        if found:
            # Take the longest contour (the main object)
            contour = np.array( max(found, key=len) )
            contour = contour.astype(np.float32)
            contours[z] = contour.tolist()
    return contours

def share_as_ndarray(img):
    """Copies a NumPy array into a same-sized newly allocated block of shared memory"""
    from appose import NDArray
    shared = NDArray(str(img.dtype), img.shape)
    shared.ndarray()[:] = img
    return shared

def add_bboxes():
    """ Reads input from rectangular ROI """
    if bboxs is not None:
        if len(bboxs) > 0:
            task.update( message=f"Adding {len(bboxs)} boxes" )
        for bbox in bboxs:
            #task.update( message=f"Adding a bbox" )
            bbox_coordinates = [
            [bbox[0], bbox[0]+1], [bbox[1], bbox[3]], [bbox[2], bbox[4]]
            ]
            task.update( message=f"positive: {bbox[5]==1}" )
            nnsession.add_bbox_interaction( bbox_coordinates, include_interaction=(bbox[5]==1) )

def add_points():
    """ Add points inupts ROI to the interactions """
    if points is not None:
        if len(points) > 0:
            task.update( message=f"Adding {len(points)} points" )
        for point in points:
            pt_coordinates = point[:3]
            #task.update(f"{pt_coordinates}")
            nnsession.add_point_interaction( pt_coordinates, include_interaction=(point[3]==1) )

def add_scribbles():
    """ Add scribbles interactions: create a binary image """
    for scribby, prop in zip( scribbles, scribbles_properties ):
        scrib_img = np.zeros( nnimgshape, dtype=np.uint8 )
        z = None
        for pt in scribby:
            scrib_img[ pt[0], pt[1], pt[2] ] = 1
            z = pt[0]
        if z is not None:
            scrib_img[z] = dilation( scrib_img[z], disk(prop[0]) )## all points have the same Z in Fiji ROI
        nnsession.add_scribble_interaction( scrib_img, include_interaction=((prop[1])==1) )

def to_5d(arr):
    """Convert 2D or 3D array to 5D"""
    while arr.ndim < 5:
        arr = np.expand_dims(arr, axis=0)
    return arr

## Add all interactions from inputs ROI in globals()
add_bboxes()
add_points()
add_scribbles()

## run the segmentation with current interactions added
result = nnsession.target_buffer.clone()
res = result.detach().cpu().numpy()
nnsession.reset_interactions() ## clear it
## send back results to appose
res = to_5d( res )
# ZYX -> TZCYX
res = np.rollaxis( res, -3, -4 )
task.outputs["binary_stack"] = share_as_ndarray( res )

