extends GutTest

# Link reads one environment variable and derives every URL from it. These tests exercise the
# derivation directly rather than the environment, because OS.set_environment does not affect
# an already-running process's view on every platform.

func test_default_is_the_local_server() -> void:
	assert_eq(Link.normalise(""), "http://127.0.0.1:7070")

func test_a_trailing_slash_is_dropped() -> void:
	assert_eq(Link.normalise("http://127.0.0.1:7070/"), "http://127.0.0.1:7070")

func test_http_becomes_ws() -> void:
	assert_eq(Link.ws_from("http://127.0.0.1:7070"), "ws://127.0.0.1:7070/ws")

func test_https_becomes_wss() -> void:
	assert_eq(Link.ws_from("https://emberdelve.example.com"), "wss://emberdelve.example.com/ws")

func test_the_live_urls_all_come_off_one_base() -> void:
	var base := Link.base_url()
	assert_eq(Link.health_url(), base + "/health")
	assert_eq(Link.tts_url(), base + "/tts")
	assert_eq(Link.ws_url(), Link.ws_from(base))
