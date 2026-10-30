"""Compatibility entry point for the superseded 42.22 reconstruction diagnostic.

The original seven checks reproduced known defects. The replacement fixture
asserts corrected behavior through mutation SQL and reconstruction round trips.
"""
from test_player_weight_roundtrip_42_22 import main

if __name__ == "__main__":
    main()
