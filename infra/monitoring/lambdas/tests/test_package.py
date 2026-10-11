import ast, os, sys, unittest
HERE = os.path.join(os.path.dirname(__file__), '..')


def sources():
    with open(os.path.join(HERE, 'deploy.py'), encoding='utf-8') as f:
        tree = ast.parse(f.read())
    for node in tree.body:
        if isinstance(node, ast.Assign) and getattr(node.targets[0], 'id', '') == 'SOURCES':
            return ast.literal_eval(node.value)


class PackageTest(unittest.TestCase):

    def test_every_runtime_module_is_packaged(self):
        """새 모듈을 만들고 SOURCES 에 안 넣으면 Lambda 가 ImportError 로 매분 죽는다."""
        runtime = {f for f in os.listdir(HERE) if f.endswith('.py') and f not in ('deploy.py', 'rearm.py')}
        self.assertEqual(set(sources()), runtime)


if __name__ == '__main__':
    unittest.main()
