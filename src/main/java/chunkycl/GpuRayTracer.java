package chunkycl;

import static org.jocl.CL.*;

import org.apache.commons.math3.util.FastMath;
import org.jocl.*;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedList;
import java.util.List;
import java.util.Scanner;

import se.llbit.chunky.block.Block;
import se.llbit.chunky.chunk.BlockPalette;
import se.llbit.chunky.renderer.scene.Scene;
import se.llbit.chunky.renderer.scene.Sun;
import se.llbit.chunky.resources.Texture;
import se.llbit.log.Log;
import se.llbit.math.Octree;
import se.llbit.math.PackedOctree;
import se.llbit.math.Vector3;
import se.llbit.util.TaskTracker;

public class GpuRayTracer {
    private cl_mem octreeDepth = null;
    private cl_mem octreeData = null;
    private cl_mem voxelLength = null;
    private cl_mem transparentArray = null;
    private cl_mem transparentLength = null;
    private cl_mem blockTextures = null;
    private cl_mem blockData = null;

    private cl_program program;
    private cl_kernel kernel;

    private cl_context context;
    private cl_command_queue commandQueue;

    private int[] version;

    public final long workgroupSize;

    private static String programSource;

    @SuppressWarnings("deprecation")
    GpuRayTracer() {
        // The platform, device type and device number
        final int platformIndex = 0;
        final long deviceType = CL_DEVICE_TYPE_ALL;
        final int deviceIndex = 0;

        // Load program source
        InputStream programStream = GpuRayTracer.class.getClassLoader().getResourceAsStream("rayTracer.cl");
        assert programStream != null;
        Scanner s = new Scanner(programStream).useDelimiter("\\A");
        programSource = s.hasNext() ? s.next() : "";

        // Enable exceptions
        CL.setExceptionsEnabled(true);

        // Obtain the number of platforms
        int[] numPlatformsArray = new int[1];
        clGetPlatformIDs(0, null, numPlatformsArray);
        int numPlatforms = numPlatformsArray[0];

        // Obtain a platform ID
        cl_platform_id[] platforms = new cl_platform_id[numPlatforms];
        clGetPlatformIDs(platforms.length, platforms, null);
        cl_platform_id platform = platforms[platformIndex];

        // Initialize the context properties
        cl_context_properties contextProperties = new cl_context_properties();
        contextProperties.addProperty(CL_CONTEXT_PLATFORM, platform);

        // Obtain the number of devices for the platform
        int[] numDevicesArray = new int[1];
        clGetDeviceIDs(platform, deviceType, 0, null, numDevicesArray);
        int numDevices = numDevicesArray[0];

        // Obtain a device ID
        cl_device_id[] devices = new cl_device_id[numDevices];
        clGetDeviceIDs(platform, deviceType, numDevices, devices, null);
        cl_device_id device = devices[deviceIndex];

        // Print out all connected devices
        System.out.println("OpenCL Devices:");
        for (int i = 0; i < numDevices; i++) {
            System.out.println("  [" + i  + "] " + getString(devices[i], CL_DEVICE_NAME));
        }

        // Print out selected device
        System.out.println("\nUsing: " + getString(device, CL_DEVICE_NAME));

        workgroupSize = getSizes(device, CL_DEVICE_MAX_WORK_GROUP_SIZE, 1)[0];

        // Create a context for the selected device
        context = clCreateContext(
                contextProperties, 1, new cl_device_id[]{device},
                null, null, null);

        // Create a command-queue for the selected device
        cl_queue_properties properties = new cl_queue_properties();

        // Get OpenCL version
        this.version = new int[2];
        String versionString = getString(device, CL_DEVICE_VERSION);
        this.version[0] = Integer.parseInt(versionString.substring(7, 8));
        this.version[1] = Integer.parseInt(versionString.substring(9, 10));
        System.out.println("       " + versionString);

        // Create command queue with correct version
        if (this.version[0] >= 2) {
            commandQueue = clCreateCommandQueueWithProperties(
                    context, device, properties, null);
        } else {
            commandQueue = clCreateCommandQueue(
                    context, device, 0, null);
        }

        // Check if version is behind
        if (this.version[0] <= 1 && this.version[1] < 2) {
            Log.error("OpenCL 1.2+ required.");
        }

        // Create the program
        program = clCreateProgramWithSource(context, 1, new String[] {programSource},
                null, null);

        // Build the program
        try {
            clBuildProgram(program, 0, null, null, null, null);
        } catch (CLException e) {
            if (e.getStatus() == CL_BUILD_PROGRAM_FAILURE) {
                // Obtain the length of the string that will be queried
                long[] size = new long[1];
                clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, 0, null, size);

                // Create a buffer of the appropriate size and fill it with the info
                byte[] buffer = new byte[(int)size[0]];
                clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, buffer.length, Pointer.to(buffer), null);

                // Create a string from the buffer (excluding the trailing \0 byte)
                System.err.println(new String(buffer, 0, buffer.length-1));
            }

            throw e;
        }

        // Create the kernel
        kernel = clCreateKernel(program, "rayTracer", null);
    }

    @SuppressWarnings("unchecked")
    public void load(Scene scene, TaskTracker.Task renderTask) {
        Octree octree;
        int[] treeData;

        // Free opencl memory if applicable
        if (this.octreeData != null) {
            clReleaseMemObject(this.octreeData);
            clReleaseMemObject(this.voxelLength);
            clReleaseMemObject(this.transparentArray);
            clReleaseMemObject(this.transparentLength);
            clReleaseMemObject(this.blockTextures);
            clReleaseMemObject(this.blockData);
        }

        renderTask.update("Loading Octree into GPU", 3, 0);

        // Obtain octree through reflection
        try {
            Field worldOctree = scene.getClass().getDeclaredField("worldOctree");
            worldOctree.setAccessible(true);
            octree = (Octree) worldOctree.get(scene);

            Field worldOctreeImplementation = octree.getClass().getDeclaredField("implementation");
            worldOctreeImplementation.setAccessible(true);
            PackedOctree packedWorldOctree = (PackedOctree) worldOctreeImplementation.get(octree);

            Field worldOctreeTreeData = packedWorldOctree.getClass().getDeclaredField("treeData");
            worldOctreeTreeData.setAccessible(true);
            treeData = (int[]) worldOctreeTreeData.get(packedWorldOctree);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
            return;
        }

        // Load bounds into memory
        this.octreeDepth = clCreateBuffer(context,
                CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                Sizeof.cl_int, Pointer.to(new int[] {octree.getDepth()}), null);

        // Load octree into texture memory for performance reasons
        // Octree data taken from packed octree is turned into a 8192 x (x) image
        // Data is loaded into the rgba channels to maximize efficiency
        cl_image_format format = new cl_image_format();
        format.image_channel_data_type = CL_SIGNED_INT32;
        format.image_channel_order = CL_RGBA;

        int[] treeDataCopy = new int[(treeData.length/8192 + 1) * 8192];
        System.arraycopy(treeData, 0, treeDataCopy, 0, treeData.length);

        cl_image_desc desc = new cl_image_desc();
        desc.image_type = CL_MEM_OBJECT_IMAGE2D;
        desc.image_width = Math.min(treeData.length, 8192);
        desc.image_height = treeData.length / 8192 + 1;

        this.octreeData = clCreateImage(context,
                CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                format, desc, Pointer.to(treeDataCopy), null);

        this.voxelLength = clCreateBuffer(context,
                CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                Sizeof.cl_int, Pointer.to(new int[] {treeData.length}), null);

        renderTask.update("Loading blocks into GPU", 3, 1);

        // Create transparent block table
        List<Integer> transparentList = new LinkedList<>();
        List<Block> blockPalette;
        BlockPalette palette = scene.getPalette();

        // Get block palette through reflection
        try {
            Field blockPaletteList = palette.getClass().getDeclaredField("palette");
            blockPaletteList.setAccessible(true);
            blockPalette = (List<Block>) blockPaletteList.get(palette);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
            return;
        }

        // Build transparent block list
        for (int i = 0; i < blockPalette.size(); i++) {
            if (palette.get(i).invisible)
                transparentList.add(i);
        }

        // Convert transparent block list into array
        int[] transparent = new int[transparentList.size()];
        for (int i = 0; i < transparent.length; i++) {
            transparent[i] = transparentList.remove(0);
        }

        // Load transparent block list onto gpu as array.
        // Size is relatively small so there is no need to load it as a texture
        this.transparentArray = clCreateBuffer(context,
                CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                (long) Sizeof.cl_int * transparent.length,
                Pointer.to(transparent), null);

        this.transparentLength = clCreateBuffer(context,
                CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, Sizeof.cl_int,
                Pointer.to(new int[] {transparent.length}), null);

        renderTask.update("Loading Block Textures into GPU", 3, 2);

        // Load all block textures into GPU texture memory
        // Load block texture data directly into an array which is dynamically sized for non-full blocks
        Texture stoneTexture = blockPalette.get(palette.stoneId).getTexture(0);
        int[] blockTexturesArray = new int[stoneTexture.getData().length * blockPalette.size()];
        int[] blockIndexesArray = new int[blockPalette.size() * 4];
        int index = 0;
        for (int i = 0; i < blockPalette.size(); i++) {
            Block block = blockPalette.get(i);
            Texture texture = block.getTexture(0);
            int[] textureData = texture.getData();

            // Resize array if necessary
            if (index + textureData.length > blockTexturesArray.length) {
                int[] tempCopyArray = new int[blockTexturesArray.length];
                System.arraycopy(blockTexturesArray, 0, tempCopyArray, 0, blockTexturesArray.length);
                blockTexturesArray = new int[blockTexturesArray.length + 4*textureData.length];
                System.arraycopy(tempCopyArray, 0, blockTexturesArray, 0, tempCopyArray.length);
            }

            // Add block texture data
            blockIndexesArray[i*4] = index;
            System.arraycopy(textureData, 0, blockTexturesArray, index, textureData.length);
            index += textureData.length;

            // Include block information in auxiliary array
            blockIndexesArray[i*4 + 1] = (int) (block.emittance * scene.getEmitterIntensity() * 256);
            blockIndexesArray[i*4 + 2] = (int) (block.specular * 256);

            // x = index, y/256 = emittance, z/256 = specular
        }

        // Copy block texture data into fitted array to prevent Segfaults
        int[] blockTexturesArrayCopy = new int[(blockTexturesArray.length/8192/3 + 1) * 8192 * 3];
        System.arraycopy(blockTexturesArray, 0, blockTexturesArrayCopy, 0, blockTexturesArray.length);

        // Load arrays as images.
        format.image_channel_data_type = CL_UNSIGNED_INT32;
        format.image_channel_order = CL_RGBA;

        desc.image_type = CL_MEM_OBJECT_IMAGE2D;
        desc.image_width = Math.min(blockTexturesArray.length/3, 8192);
        desc.image_height = blockTexturesArray.length / 8192 / 3 + 1;

        blockTextures = clCreateImage(context,
                CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, format, desc,
                Pointer.to(blockTexturesArrayCopy), null);

        format.image_channel_data_type = CL_SIGNED_INT32;
        format.image_channel_order = CL_RGBA;

        desc.image_type = CL_MEM_OBJECT_IMAGE1D;
        desc.image_width = blockIndexesArray.length / 3;
        blockData = clCreateImage(context,
                CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, format, desc,
                Pointer.to(blockIndexesArray), null);

        renderTask.update("Loading GPU", 3, 3);
    }

    public float[] rayTrace(float[] rayDirs, Vector3 origin, int seed, int rayDepth, boolean preview, Sun sun) {
        // Results array
        float[] rayRes = new float[rayDirs.length];

        float[] rayPos = new float[3];
        rayPos[0] = (float) origin.x;
        rayPos[1] = (float) origin.y;
        rayPos[2] = (float) origin.z;

        float[] sunPos = new float[3];
        sunPos[0] = (float) (FastMath.cos(sun.getAzimuth()) * FastMath.cos(sun.getAltitude()));
        sunPos[1] = (float) (FastMath.sin(sun.getAltitude()));
        sunPos[2] = (float) (FastMath.sin(sun.getAzimuth()) * FastMath.cos(sun.getAltitude()));

        Pointer srcRayRes = Pointer.to(rayRes);

        // Transfer arguments to GPU memory
        cl_mem clRayPos = clCreateBuffer(context,
                CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                (long) Sizeof.cl_float * rayPos.length, Pointer.to(rayPos), null);
        cl_mem clNormCoords = clCreateBuffer(context,
                CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                (long) Sizeof.cl_float * rayDirs.length, Pointer.to(rayDirs), null);
        cl_mem clSeed = clCreateBuffer(context,
                CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                Sizeof.cl_int, Pointer.to(new int[] {seed}), null);
        cl_mem clRayDepth = clCreateBuffer(context,
                CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                Sizeof.cl_int, Pointer.to(new int[] {rayDepth}), null);
        cl_mem clSunPos = clCreateBuffer(context,
                CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                Sizeof.cl_float * 3, Pointer.to(sunPos), null);
        cl_mem clPreview = clCreateBuffer(context,
                CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                Sizeof.cl_int, Pointer.to(new int[] {preview ? 1 : 0}), null);

        cl_mem clRayRes = clCreateBuffer(context, CL_MEM_WRITE_ONLY,
                (long) Sizeof.cl_float * rayRes.length, null, null);

        // Set the arguments
        clSetKernelArg(kernel, 0, Sizeof.cl_mem, Pointer.to(clRayPos));
        clSetKernelArg(kernel, 1, Sizeof.cl_mem, Pointer.to(clNormCoords));
        clSetKernelArg(kernel, 2, Sizeof.cl_mem, Pointer.to(octreeDepth));
        clSetKernelArg(kernel, 3, Sizeof.cl_mem, Pointer.to(octreeData));
        clSetKernelArg(kernel, 4, Sizeof.cl_mem, Pointer.to(voxelLength));
        clSetKernelArg(kernel, 5, Sizeof.cl_mem, Pointer.to(transparentArray));
        clSetKernelArg(kernel, 6, Sizeof.cl_mem, Pointer.to(transparentLength));
        clSetKernelArg(kernel, 7, Sizeof.cl_mem, Pointer.to(blockTextures));
        clSetKernelArg(kernel, 8, Sizeof.cl_mem, Pointer.to(blockData));
        clSetKernelArg(kernel, 9, Sizeof.cl_mem, Pointer.to(clSeed));
        clSetKernelArg(kernel, 10, Sizeof.cl_mem, Pointer.to(clRayDepth));
        clSetKernelArg(kernel, 11, Sizeof.cl_mem, Pointer.to(clPreview));
        clSetKernelArg(kernel, 12, Sizeof.cl_mem, Pointer.to(clSunPos));
        clSetKernelArg(kernel, 13, Sizeof.cl_mem, Pointer.to(clRayRes));

        // Work size = rays
        long[] global_work_size = new long[]{rayRes.length/3};

        // Execute the program
        clEnqueueNDRangeKernel(commandQueue, kernel, 1, null, global_work_size,
                null, 0, null, null);

        // Get the results
        try {
            clEnqueueReadBuffer(commandQueue, clRayRes, CL_TRUE, 0, (long) Sizeof.cl_float * rayRes.length,
                    srcRayRes, 0, null, null);
        } catch (CLException e) {
            throw e;
        }

        // Clean up
        clReleaseMemObject(clRayPos);
        clReleaseMemObject(clNormCoords);
        clReleaseMemObject(clRayRes);
        clReleaseMemObject(clSeed);
        clReleaseMemObject(clRayDepth);
        clReleaseMemObject(clPreview);

        return rayRes;
    }

    /** Get a string from OpenCL */
    private static String getString(cl_device_id device, int paramName)
    {
        // Obtain the length of the string that will be queried
        long[] size = new long[1];
        clGetDeviceInfo(device, paramName, 0, null, size);

        // Create a buffer of the appropriate size and fill it with the info
        byte[] buffer = new byte[(int)size[0]];
        clGetDeviceInfo(device, paramName, buffer.length, Pointer.to(buffer), null);

        // Create a string from the buffer (excluding the trailing \0 byte)
        return new String(buffer, 0, buffer.length-1);
    }

    /** get a long(array) from OpenCL */
    static long[] getSizes(cl_device_id device, int paramName, int numValues)
    {
        // The size of the returned data has to depend on
        // the size of a size_t, which is handled here
        ByteBuffer buffer = ByteBuffer.allocate(
                numValues * Sizeof.size_t).order(ByteOrder.nativeOrder());
        clGetDeviceInfo(device, paramName, Sizeof.size_t * numValues,
                Pointer.to(buffer), null);
        long values[] = new long[numValues];
        if (Sizeof.size_t == 4)
        {
            for (int i=0; i<numValues; i++)
            {
                values[i] = buffer.getInt(i * Sizeof.size_t);
            }
        }
        else
        {
            for (int i=0; i<numValues; i++)
            {
                values[i] = buffer.getLong(i * Sizeof.size_t);
            }
        }
        return values;
    }
}
