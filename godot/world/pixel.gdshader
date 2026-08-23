shader_type spatial;
// canvas_item rejects hint_depth_texture in Godot 4.7.2 — spatial overlay is the engine path.
render_mode unshaded, fog_disabled, shadows_disabled, ambient_light_disabled,
		depth_draw_never, depth_test_disabled, cull_disabled;

uniform sampler2D screen_texture : hint_screen_texture, repeat_disable, filter_nearest;
uniform sampler2D depth_texture : hint_depth_texture, repeat_disable, filter_nearest;
uniform sampler2D normal_roughness_texture : hint_normal_roughness_texture, repeat_disable, filter_nearest;

uniform float normal_edge_strength : hint_range(0.0, 2.0) = 0.5;
uniform float depth_edge_strength : hint_range(0.0, 2.0) = 0.25;

void vertex() {
	POSITION = vec4(VERTEX.xy, 1.0, 1.0);
}

float get_depth(vec2 uv) {
	// Reverse-Z: 1 at near, 0 at far. Three.js depth is 0 at near, 1 at far.
	return 1.0 - textureLod(depth_texture, uv, 0.0).r;
}

vec3 get_normal(vec2 uv) {
	return textureLod(normal_roughness_texture, uv, 0.0).rgb * 2.0 - 1.0;
}

float depth_edge_indicator(vec2 uv, vec2 px, float depth) {
	float diff = 0.0;
	diff += clamp(get_depth(uv + vec2(1.0, 0.0) * px) - depth, 0.0, 1.0);
	diff += clamp(get_depth(uv + vec2(-1.0, 0.0) * px) - depth, 0.0, 1.0);
	diff += clamp(get_depth(uv + vec2(0.0, 1.0) * px) - depth, 0.0, 1.0);
	diff += clamp(get_depth(uv + vec2(0.0, -1.0) * px) - depth, 0.0, 1.0);
	return floor(smoothstep(0.01, 0.02, diff) * 2.0) / 2.0;
}

float neighbor_normal_edge_indicator(vec2 uv, vec2 px, vec2 offset, float depth, vec3 normal) {
	vec2 sample_uv = uv + offset * px;
	float depth_diff = get_depth(sample_uv) - depth;
	vec3 neighbor_normal = get_normal(sample_uv);
	vec3 normal_edge_bias = vec3(1.0, 1.0, 1.0);
	float normal_diff = dot(normal - neighbor_normal, normal_edge_bias);
	float normal_indicator = clamp(smoothstep(-0.01, 0.01, normal_diff), 0.0, 1.0);
	float depth_indicator = clamp(sign(depth_diff * 0.25 + 0.0025), 0.0, 1.0);
	return (1.0 - dot(normal, neighbor_normal)) * depth_indicator * normal_indicator;
}

float normal_edge_indicator(vec2 uv, vec2 px, float depth, vec3 normal) {
	float indicator = 0.0;
	indicator += neighbor_normal_edge_indicator(uv, px, vec2(0.0, -1.0), depth, normal);
	indicator += neighbor_normal_edge_indicator(uv, px, vec2(0.0, 1.0), depth, normal);
	indicator += neighbor_normal_edge_indicator(uv, px, vec2(-1.0, 0.0), depth, normal);
	indicator += neighbor_normal_edge_indicator(uv, px, vec2(1.0, 0.0), depth, normal);
	return step(0.1, indicator);
}

void fragment() {
	vec4 texel = textureLod(screen_texture, SCREEN_UV, 0.0);
	vec2 px = 1.0 / VIEWPORT_SIZE;
	float depth = 0.0;
	vec3 normal = vec3(0.0);
	if (depth_edge_strength > 0.0 || normal_edge_strength > 0.0) {
		depth = get_depth(SCREEN_UV);
		normal = get_normal(SCREEN_UV);
	}
	float dei = 0.0;
	if (depth_edge_strength > 0.0) {
		dei = depth_edge_indicator(SCREEN_UV, px, depth);
	}
	float nei = 0.0;
	if (normal_edge_strength > 0.0) {
		nei = normal_edge_indicator(SCREEN_UV, px, depth, normal);
	}
	float strength = dei > 0.0
			? (1.0 - depth_edge_strength * dei)
			: (1.0 + normal_edge_strength * nei);
	ALBEDO = texel.rgb * strength;
	ALPHA = 1.0;
}
