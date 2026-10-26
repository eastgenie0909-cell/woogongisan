"""Stable application entry point. Model selection lives in active_model.json."""
from models.registry import load_model
from models.core_v1 import ASSETS

def evaluate(p, btc, model=None):
    return (model or load_model()).evaluate(p, btc)
