// ---- super resolution (the pause menu's Super resolution, settings.txt sr): the game's picture goes into the window's larger picture rectangle through
// SGSR 1.0's edge-direction filter (dm/android/shaders/sr.frag: one pass, its 12 taps as four textureGather reads, upscale and sharpening together)
// instead of the hardware blit, which only bilinearly enlarges. It is one draw in the present's own submission, into the swapchain image (or the
// pre-rotated copy) and with no intermediate image: nothing of the game's picture is written to memory and read back. The overlay draws in the same
// rendering scope after it. The output goes through the tap filter in linear light for an sRGB window texture. At Sharpness 0 the final sharpening
// step stays inside the texels' own range (minY..maxY), so a bright edge has no halo in either encoding; higher Sharpness raises its multiplier
// (2.0 to 8.0) and lets it pass that range by up to 35 percent ----
static struct { VkDescriptorSetLayout dsl; VkPipelineLayout layout; VkDescriptorPool pool; VkDescriptorSet set[8]; VkSampler samp; VkPipeline pipe; VkFormat pfmt; } g_sr;
static int g_sr_dead;   // a device that refused the objects once is not asked again: the blit presents every frame
typedef struct { float vp[4], dst[4], uv[4], flip, sharp; } sr_push;   // sr.vert, sr.frag: the image's texel size and size, the picture rectangle in clip space, its rectangle in the image (fractions), the image's row order
static int sr_on(void) {
    static int env = -2;
    if (env == -2) { const char *e = getenv("DM_VK_SR"); env = e ? atoi(e) != 0 : -1; }   // DM_VK_SR=0/1: over the menu, for a device loop (read once)
    return env < 0 ? dm_overlay_sr() : env;
}
static int sr_sharp(void) {   // the pause menu's Sharpness, percent (settings.txt sharpness=); DM_VK_SR_SHARP overrides it for a run
    const char *e = getenv("DM_VK_SR_SHARP");
    int v = e ? atoi(e) : dm_overlay_sharp();
    return v < 0 ? 0 : v > 100 ? 100 : v;
}
static int sr_ready(uint32_t k, vkimg_t *im) {   // the pipeline and present slot k's descriptor set; 0: the blit draws this frame instead
    if (g_sr_dead) return 0;
    if (!g_sr.dsl) {
        VkDescriptorSetLayoutBinding b = {0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT, NULL};
        VkDescriptorSetLayoutCreateInfo li = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO, NULL, 0, 1, &b};
        VkPushConstantRange pr = {VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(sr_push)};
        // LINEAR with CLAMP_TO_EDGE: the base sample sits on the four texels its coordinate snaps to (a light prefilter, and never a mid-texel
        // sample), and the edge taps repeat the picture's last row or column at its border
        VkSamplerCreateInfo si = {VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO, NULL, 0, VK_FILTER_LINEAR, VK_FILTER_LINEAR, VK_SAMPLER_MIPMAP_MODE_NEAREST,
            VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE};
        VkDescriptorPoolSize ps = {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 8};
        VkDescriptorPoolCreateInfo pi = {VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO, NULL, 0, 8, 1, &ps};
        VkResult r = vkCreateDescriptorSetLayout(V.dev, &li, NULL, &g_sr.dsl);
        if (!r) { VkPipelineLayoutCreateInfo pl = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, NULL, 0, 1, &g_sr.dsl, 1, &pr}; r = vkCreatePipelineLayout(V.dev, &pl, NULL, &g_sr.layout); }
        if (!r) r = vkCreateSampler(V.dev, &si, NULL, &g_sr.samp);
        if (!r) r = vkCreateDescriptorPool(V.dev, &pi, NULL, &g_sr.pool);
        if (!r) { VkDescriptorSetLayout l[8] = {g_sr.dsl, g_sr.dsl, g_sr.dsl, g_sr.dsl, g_sr.dsl, g_sr.dsl, g_sr.dsl, g_sr.dsl};
                  VkDescriptorSetAllocateInfo ai = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO, NULL, g_sr.pool, 8, l}; r = vkAllocateDescriptorSets(V.dev, &ai, g_sr.set); }
        if (r) { g_sr_dead = 1; VKRED_ONCE(113, "super resolution: descriptor set, layout, sampler or pool: %d; the blit scales the picture", r); return 0; }
    }
    if (g_sr.pfmt != g_sfmt) {   // the pipeline renders into the swapchain's (or the rotated copy's) format
        if (g_sr.pipe) { vkDestroyPipeline(V.dev, g_sr.pipe, NULL); g_sr.pipe = VK_NULL_HANDLE; }
        VkShaderModule vm = VK_NULL_HANDLE, fm = VK_NULL_HANDLE;
        VkShaderModuleCreateInfo vc = {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, NULL, 0, sizeof sr_vert_spv, sr_vert_spv};
        VkShaderModuleCreateInfo fc = {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, NULL, 0, sizeof sr_frag_spv, sr_frag_spv};
        VkResult r = vkCreateShaderModule(V.dev, &vc, NULL, &vm); if (!r) r = vkCreateShaderModule(V.dev, &fc, NULL, &fm);
        VkPipelineShaderStageCreateInfo ss[2] = {{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, NULL, 0, VK_SHADER_STAGE_VERTEX_BIT, vm, "main", NULL},
                                                {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, NULL, 0, VK_SHADER_STAGE_FRAGMENT_BIT, fm, "main", NULL}};
        VkPipelineVertexInputStateCreateInfo vi = {VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
        VkPipelineInputAssemblyStateCreateInfo ia = {VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO, NULL, 0, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, 0};
        VkPipelineViewportStateCreateInfo vs = {VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO, NULL, 0, 1, NULL, 1, NULL};
        VkPipelineRasterizationStateCreateInfo rs = {VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO, NULL, 0, 0, 0, VK_POLYGON_MODE_FILL, VK_CULL_MODE_NONE, VK_FRONT_FACE_COUNTER_CLOCKWISE, 0, 0, 0, 0, 1};
        VkPipelineMultisampleStateCreateInfo ms = {VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO, NULL, 0, VK_SAMPLE_COUNT_1_BIT};
        VkPipelineColorBlendAttachmentState ba = {0, 0, 0, 0, 0, 0, 0, 0xf};   // opaque: the cleared borders stay, the picture replaces its rectangle
        VkPipelineColorBlendStateCreateInfo cbs = {VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO, NULL, 0, 0, 0, 1, &ba};
        VkDynamicState dyn[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR}; VkPipelineDynamicStateCreateInfo dy = {VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO, NULL, 0, 2, dyn};
        VkPipelineRenderingCreateInfo rci = {VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO, NULL, 0, 1, &g_sfmt};
        VkGraphicsPipelineCreateInfo ci = {VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO, &rci, 0, 2, ss, &vi, &ia, NULL, &vs, &rs, &ms, NULL, &cbs, &dy, g_sr.layout, VK_NULL_HANDLE};
        if (!r) r = vkCreateGraphicsPipelines(V.dev, V.pcache, 1, &ci, NULL, &g_sr.pipe);
        vk_pipeline_cache_changed();
        if (vm) vkDestroyShaderModule(V.dev, vm, NULL); if (fm) vkDestroyShaderModule(V.dev, fm, NULL);
        if (r) { g_sr_dead = 1; g_sr.pipe = VK_NULL_HANDLE; VKRED_ONCE(114, "super resolution: pipeline for format %d: %d; the blit scales the picture", g_sfmt, r); return 0; }
        g_sr.pfmt = g_sfmt;
    }
    // the view lives with the image (img_drop frees it), so a recycled window texture's handle can never be sampled through an older image's view
    if (!im->full) {
        VkImageViewCreateInfo vi = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, NULL, 0, im->img, VK_IMAGE_VIEW_TYPE_2D, im->fmt, {0}, {VK_IMAGE_ASPECT_COLOR_BIT, 0, im->levels, 0, 1}};
        if (vkCreateImageView(V.dev, &vi, NULL, &im->full)) { im->full = VK_NULL_HANDLE; VKRED_ONCE(115, "super resolution: a view of the %ux%u picture: the blit scales it", im->w, im->h); return 0; }
    }
    VkDescriptorImageInfo ii = {g_sr.samp, im->full, VK_IMAGE_LAYOUT_GENERAL};   // version 1 keeps every image in GENERAL
    VkWriteDescriptorSet wr = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, NULL, g_sr.set[k], 0, 0, 1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &ii};
    vkUpdateDescriptorSets(V.dev, 1, &wr, 0, NULL);   // this slot's fence was just waited, so its previous descriptor is no longer in use
    return 1;
}
