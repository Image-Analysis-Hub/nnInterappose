task.update( message=f"nnInteractive segmentation.." )

from skimage.measure import find_contours

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

def to_5d(arr):
    """Convert 2D or 3D array to 5D"""
    while arr.ndim < 5:
        arr = np.expand_dims(arr, axis=0)
    return arr

## Add all interactions from inputs ROI in globals()
add_bboxes()
add_points()
## run the segmentation with current interactions added
result = nnsession.target_buffer.clone()
res = result.detach().cpu().numpy()
nnsession.reset_interactions() ## clear it
## send back results to appose
res = to_5d( res )
# ZYX -> TZCYX
res = np.rollaxis( res, -3, -4 )
task.outputs["binary_stack"] = share_as_ndarray( res )

