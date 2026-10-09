import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('deployment', Path(__file__).resolve().parents[1] / 'deploy/configure.py')
deployment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deployment)


class DeploymentTests(unittest.TestCase):
    def check(self, config):
        deployment.check_serve(config, 'recorder.example.ts.net', 443, 'http://127.0.0.1:8080')

    def test_empty_config_is_available(self):
        self.check({})

    def test_existing_recorder_and_other_paths_are_preserved(self):
        self.check({'TCP': {'443': {'HTTPS': True}}, 'Web': {'recorder.example.ts.net:443': {
            'Handlers': {'/': {'Proxy': 'http://127.0.0.1:8080'}, '/other': {'Proxy': 'http://127.0.0.1:3000'}}}}})

    def test_other_root_service_is_not_overwritten(self):
        with self.assertRaises(ValueError):
            self.check({'Web': {'recorder.example.ts.net:443': {'Handlers': {'/': {'Proxy': 'http://127.0.0.1:3000'}}}}})

    def test_other_tcp_service_is_not_overwritten(self):
        with self.assertRaises(ValueError):
            self.check({'TCP': {'443': {'TCPForward': '127.0.0.1:3000'}}})

    def test_funnel_is_rejected_for_recorder_port(self):
        with self.assertRaises(ValueError):
            self.check({'AllowFunnel': {'recorder.example.ts.net:443': True}})
