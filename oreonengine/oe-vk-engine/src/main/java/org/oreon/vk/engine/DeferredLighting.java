package org.oreon.vk.engine;

import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkQueue;
import org.oreon.core.context.EngineContext;
import org.oreon.core.vk.command.CommandBuffer;
import org.oreon.core.vk.command.SubmitInfo;
import org.oreon.core.vk.context.VkContext;
import org.oreon.core.vk.descriptor.DescriptorPool;
import org.oreon.core.vk.descriptor.DescriptorSet;
import org.oreon.core.vk.descriptor.DescriptorSetLayout;
import org.oreon.core.vk.device.VkDeviceBundle;
import org.oreon.core.vk.image.VkImage;
import org.oreon.core.vk.image.VkImageView;
import org.oreon.core.vk.pipeline.ShaderModule;
import org.oreon.core.vk.pipeline.VkPipeline;
import org.oreon.core.vk.util.VkUtil;
import org.oreon.core.vk.wrapper.command.ComputeCmdBuffer;
import org.oreon.core.vk.wrapper.image.Image2DDeviceLocal;
import org.oreon.core.vk.wrapper.shader.ComputeShader;

import lombok.Getter;

public class DeferredLighting {
	
	private VkQueue queue;
	
	private VkImage deferredLightingSceneImage;
	@Getter
	private VkImageView deferredLightingSceneImageView;
	private VkPipeline computePipeline;
	private DescriptorSet descriptorSet;
	private DescriptorSetLayout descriptorSetLayout;
	private CommandBuffer cmdBuffer;
	private SubmitInfo submitInfo;

	public DeferredLighting(VkDeviceBundle deviceBundle,
			int width, int height, VkImageView albedoImageView,
			VkImageView worldPositionImageView, VkImageView normalImageView,
			VkImageView specularEmissionImageView, VkImageView sampleCoverageImageView) {
		
		VkDevice device = deviceBundle.getLogicalDevice().getHandle();
		VkPhysicalDeviceMemoryProperties memoryProperties = deviceBundle.getPhysicalDevice().getMemoryProperties();
		DescriptorPool descriptorPool = deviceBundle.getLogicalDevice().getDescriptorPool(Thread.currentThread().getId());
		queue = deviceBundle.getLogicalDevice().getComputeQueue();
		
		deferredLightingSceneImage = new Image2DDeviceLocal(device, memoryProperties, 
				width, height, VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT
				| VK_IMAGE_USAGE_SAMPLED_BIT);
		deferredLightingSceneImageView = new VkImageView(device,
				VK_FORMAT_R16G16B16A16_SFLOAT, deferredLightingSceneImage.getHandle(),
				VK_IMAGE_ASPECT_COLOR_BIT);
		
		descriptorSetLayout = new DescriptorSetLayout(device, 6);
		descriptorSetLayout.addLayoutBinding(0, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
	    		VK_SHADER_STAGE_COMPUTE_BIT);
		descriptorSetLayout.addLayoutBinding(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
	    		VK_SHADER_STAGE_COMPUTE_BIT);
		descriptorSetLayout.addLayoutBinding(2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
	    		VK_SHADER_STAGE_COMPUTE_BIT);
		descriptorSetLayout.addLayoutBinding(3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
	    		VK_SHADER_STAGE_COMPUTE_BIT);
		descriptorSetLayout.addLayoutBinding(4, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
	    		VK_SHADER_STAGE_COMPUTE_BIT);
		descriptorSetLayout.addLayoutBinding(5, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
	    		VK_SHADER_STAGE_COMPUTE_BIT);
		descriptorSetLayout.create();

		descriptorSet = new DescriptorSet(device,
				descriptorPool.getHandle(), descriptorSetLayout.getHandlePointer());
		descriptorSet.updateDescriptorImageBuffer(deferredLightingSceneImageView.getHandle(),
		    	VK_IMAGE_LAYOUT_GENERAL, -1, 0,
		    	VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
		descriptorSet.updateDescriptorImageBuffer(albedoImageView.getHandle(),
				VK_IMAGE_LAYOUT_GENERAL, -1, 1,
	    		VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
		descriptorSet.updateDescriptorImageBuffer(worldPositionImageView.getHandle(),
	    		VK_IMAGE_LAYOUT_GENERAL, -1, 2,
	    		VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
		descriptorSet.updateDescriptorImageBuffer(normalImageView.getHandle(),
	    		VK_IMAGE_LAYOUT_GENERAL, -1, 3,
	    		VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
		descriptorSet.updateDescriptorImageBuffer(specularEmissionImageView.getHandle(),
	    		VK_IMAGE_LAYOUT_GENERAL, -1, 4,
	    		VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
		descriptorSet.updateDescriptorImageBuffer(sampleCoverageImageView.getHandle(),
	    		VK_IMAGE_LAYOUT_GENERAL, -1, 5,
	    		VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
		
		List<DescriptorSet> descriptorSets = new ArrayList<DescriptorSet>();
		List<DescriptorSetLayout> descriptorSetLayouts = new ArrayList<DescriptorSetLayout>();
		
		descriptorSets.add(VkContext.getCamera().getDescriptor().getSet());
		descriptorSets.add(descriptorSet);
		descriptorSetLayouts.add(VkContext.getCamera().getDescriptor().getLayout());
		descriptorSetLayouts.add(descriptorSetLayout);
		
		int pushConstantRange = Float.BYTES * 1 + Integer.BYTES * 1;
		ByteBuffer pushConstants = memAlloc(pushConstantRange);
		pushConstants.putInt(EngineContext.getConfig().getMultisamples());
		pushConstants.putFloat(EngineContext.getConfig().getSightRange());
		pushConstants.flip();
		
		ShaderModule shader = new ComputeShader(device, "shaders/deferredLighting.comp.spv");
		
		computePipeline = new VkPipeline(device);
		computePipeline.setPushConstantsRange(VK_SHADER_STAGE_COMPUTE_BIT, pushConstantRange);
		computePipeline.setLayout(VkUtil.createLongBuffer(descriptorSetLayouts));
		computePipeline.createComputePipeline(shader);
		
		cmdBuffer = new ComputeCmdBuffer(device,
				deviceBundle.getLogicalDevice().getComputeCommandPool().getHandle(),
				computePipeline.getHandle(), computePipeline.getLayoutHandle(),
				VkUtil.createLongArray(descriptorSets), width/16, height/16, 1,
				pushConstants, VK_SHADER_STAGE_COMPUTE_BIT);
		
		submitInfo = new SubmitInfo();
		submitInfo.setCommandBuffers(cmdBuffer.getHandlePointer());
		
		shader.destroy();
	}
	
	public void render(){
		
		submitInfo.submit(queue);
	}
	
	public void shutdown(){
		deferredLightingSceneImage.destroy();
		deferredLightingSceneImageView.destroy();
		computePipeline.destroy();
		descriptorSet.destroy();
		descriptorSetLayout.destroy();
		cmdBuffer.destroy();
	}
}
