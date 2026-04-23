task.update( message=f"session: {nnsession}" )

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
        for bbox, positive in zip(bboxs, positives):
            task.update( message=f"Adding a bbox" )
            bbox_coordinates = [
            [bbox[0], bbox[0]], [bbox[1], bbox[3]], [bbox[2], bbox[4]]
            ]
            nnsession.add_bbox_interaction( bbox_coordinates, include_interaction=positive )

def to_5d(arr):
    """Convert 2D or 3D array to 5D"""
    while arr.ndim < 5:
        arr = np.expand_dims(arr, axis=0)
    return arr

add_bboxes()
## run the segmentation with current interactions added
result = nnsession.target_buffer.clone()
res = result.detach().cpu().numpy()
nnsession.reset_interactions() ## clear it
res = to_5d( res )
# ZYX -> TZCYX
res = np.rollaxis( res, -3, -4 )
task.outputs["binary_stack"] = share_as_ndarray( res )
#coordinates = get_contours_from_zstack( res )
#task.outputs["coordinates"] = coordinates

