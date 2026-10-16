"""Select implemented operations and their unchanged published schema closure."""
from copy import deepcopy

HTTP_METHODS={'get','put','post','delete','options','head','patch','trace'}

def select_paths(snapshot, operation_ids):
    wanted=set(operation_ids)
    found=set()
    paths={}
    for path,item in snapshot['paths'].items():
        operations={}
        for method,operation in item.items():
            if method not in HTTP_METHODS or operation.get('operationId') not in wanted:
                continue
            identity=operation['operationId']
            if identity in found:
                raise ValueError(f'Duplicate operation identity: {identity}')
            found.add(identity)
            operations[method]=deepcopy(operation)
        if operations:
            paths[path]={key:deepcopy(value) for key,value in item.items() if key not in HTTP_METHODS}
            paths[path].update(operations)
    if found!=wanted:
        raise ValueError(f'Missing contract operations: {wanted-found}')
    return paths

def schema_closure(snapshot, paths):
    required=set()
    schemas=snapshot['components']['schemas']
    def visit(value):
        if isinstance(value,dict):
            reference=value.get('$ref','')
            if reference.startswith('#/components/schemas/'):
                name=reference.rsplit('/',1)[-1]
                if name not in required:
                    if name not in schemas:
                        raise ValueError(f'Missing referenced schema: {name}')
                    required.add(name)
                    visit(schemas[name])
            for child in value.values():
                visit(child)
        elif isinstance(value,list):
            for child in value:
                visit(child)
    visit(paths)
    return {name:deepcopy(schemas[name]) for name in sorted(required)}
