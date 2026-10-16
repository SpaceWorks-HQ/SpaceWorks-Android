"""Shared GET paths must not generate unrelated POST/write-only models."""
from copy import deepcopy
from pathlib import Path
import importlib.util
import unittest

SPEC=importlib.util.spec_from_file_location('selection',Path(__file__).resolve().parents[1]/'contract_selection.py')
S=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(S)

class ContractSelectionTest(unittest.TestCase):
    def snapshot(self):
        return {'paths':{'/spaces':{
            'summary':'Shared route','description':'Owner description',
            'parameters':[{'name':'maker','in':'header','schema':{'type':'integer'}}],
            'servers':[{'url':'https://owner.invalid'}],
            'get':{'operationId':'read','security':[{'jwtAuth':[]}],'responses':{'200':{'schema':{'$ref':'#/components/schemas/Read'}}}},
            'post':{'operationId':'write','requestBody':{'schema':{'$ref':'#/components/schemas/Write'}}},
        }},'components':{'schemas':{
            'Read':{'properties':{'shared':{'$ref':'#/components/schemas/Shared'}}},
            'Shared':{'properties':{'parent':{'$ref':'#/components/schemas/Read'}}},
            'Write':{'properties':{'amount':{'type':'number','default':'0.00'}}},
            'Unused':{'type':'string'},
        }}}

    def test_get_preserves_path_metadata_and_excludes_post_only_schemas_without_mutation(self):
        snapshot=self.snapshot();before=deepcopy(snapshot)
        paths=S.select_paths(snapshot,{'read'})
        self.assertEqual({'read'},{op['operationId'] for item in paths.values() for method,op in item.items() if method in S.HTTP_METHODS})
        self.assertNotIn('post',paths['/spaces'])
        for key in ['summary','description','parameters','servers','get']:
            self.assertEqual(snapshot['paths']['/spaces'][key],paths['/spaces'][key])
        closure=S.schema_closure(snapshot,paths)
        self.assertEqual({'Read','Shared'},set(closure))
        paths['/spaces']['parameters'].clear();closure['Read']['properties'].clear()
        self.assertEqual(snapshot,before)

    def test_requested_siblings_remain_exact_and_recursive_schema_cycle_terminates(self):
        snapshot=self.snapshot();paths=S.select_paths(snapshot,{'read','write'})
        self.assertEqual({'get','post'},set(paths['/spaces'])&S.HTTP_METHODS)
        self.assertEqual({'Read','Shared','Write'},set(S.schema_closure(snapshot,paths)))

    def test_missing_duplicate_operations_and_dangling_refs_fail_closed(self):
        snapshot=self.snapshot()
        with self.assertRaisesRegex(ValueError,'Missing contract operations'):S.select_paths(snapshot,{'missing'})
        snapshot['paths']['/duplicate']=deepcopy(snapshot['paths']['/spaces'])
        with self.assertRaisesRegex(ValueError,'Duplicate operation identity'):S.select_paths(snapshot,{'read'})
        del snapshot['paths']['/duplicate'];del snapshot['components']['schemas']['Shared']
        with self.assertRaisesRegex(ValueError,'Missing referenced schema'):S.schema_closure(snapshot,S.select_paths(snapshot,{'read'}))
