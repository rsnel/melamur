# mod_wsgi entry point. WSGIScriptAlias in melamur-apache.conf points here.
import os, sys
sys.path.insert(0, os.path.dirname(__file__))
from melamur_aggregator import application  # noqa: E402  (mod_wsgi imports `application`)
