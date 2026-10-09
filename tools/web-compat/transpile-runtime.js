// Runs in QuickJS without executing the supplied website code.
globalThis.ntvTransform=function(source,filename,module){
  var started=Date.now(),syntaxMs=0;
  if(!module){
    try{acorn.parse(source,{ecmaVersion:5,sourceType:'script',allowHashBang:false});
      return JSON.stringify({code:source,changed:false,module:false,syntaxMs:Date.now()-started,transformMs:0});
    }catch(unsupported){}
  }
  syntaxMs=Date.now()-started;started=Date.now();
  // Avoid walking every AST node through dozens of irrelevant transforms.
  // The final ES5 parse is mandatory; uncovered syntax falls back to preset-env.
  var needed={},all=false;
  function need(name){needed['transform-'+name]=true;}
  function scan(node,parent){
    if(!node||typeof node!=='object')return;
    if(Array.isArray(node)){for(var i=0;i<node.length;i++)scan(node[i],parent);return;}
    var type=node.type;
    if(type==='VariableDeclaration'&&node.kind!=='var')need('block-scoping');
    if(type==='ArrowFunctionExpression')need('arrow-functions');
    if(type==='ClassDeclaration'||type==='ClassExpression')need('classes');
    if(type==='PropertyDefinition'){need('class-properties');if(node.key&&node.key.type==='PrivateIdentifier')need('private-methods');}
    if(type==='StaticBlock')need('class-static-block');
    if(type==='PrivateIdentifier'){need('private-methods');need('private-property-in-object');}
    if(type==='Property'&&(node.shorthand||node.method))need('shorthand-properties');
    if(type==='Property'&&node.computed)need('computed-properties');
    if(type==='ObjectPattern'||type==='ArrayPattern')need('destructuring');
    if(type==='AssignmentPattern'||type==='RestElement')need('parameters');
    if(type==='SpreadElement')need('spread');
    if((type==='SpreadElement'||type==='RestElement')&&parent&&/^(ObjectExpression|ObjectPattern)$/.test(parent.type))need('object-rest-spread');
    if(type==='ForOfStatement'){need('for-of');if(node.await)need('async-generator-functions');}
    if(type==='TemplateLiteral'||type==='TaggedTemplateExpression')need('template-literals');
    if(node.async){need('async-to-generator');need('regenerator');if(node.generator)need('async-generator-functions');}
    if(node.generator)need('regenerator');
    if(type==='BinaryExpression'&&node.operator==='**')need('exponentiation-operator');
    if(type==='LogicalExpression'&&node.operator==='??')need('nullish-coalescing-operator');
    if(type==='AssignmentExpression'&&/^(\\?\\?=|&&=|\\|\\|=)$/.test(node.operator))need('logical-assignment-operators');
    if(type==='ChainExpression'||node.optional)need('optional-chaining');
    if(type==='CatchClause'&&!node.param)need('optional-catch-binding');
    if(type==='Super')need('object-super');
    if(type==='MetaProperty'&&node.meta.name==='new')need('new-target');
    if(node.regex){all=true;}
    for(var key in node)if(key!=='start'&&key!=='end')scan(node[key],node);
  }
  try{scan(acorn.parse(source,{ecmaVersion:'latest',sourceType:module?'module':'script'}),null);}catch(parseError){all=true;}
  var order=['class-static-block','private-property-in-object','private-methods','class-properties','object-rest-spread','optional-chaining','nullish-coalescing-operator','logical-assignment-operators','async-generator-functions','async-to-generator','arrow-functions','parameters','classes','object-super','for-of','spread','destructuring','computed-properties','shorthand-properties','template-literals','exponentiation-operator','optional-catch-binding','new-target','regenerator','block-scoping'];
  var selected=[];for(var j=0;j<order.length;j++)if(needed['transform-'+order[j]])selected.push('transform-'+order[j]);
  if(module)selected.push('transform-modules-systemjs');
  var options={
    filename:filename,sourceType:module?'module':'script',babelrc:false,configFile:false,
    comments:false,compact:true,ast:false,
    plugins:[function(api){return {visitor:{CallExpression:function(path){
      if(path.node.callee.type==='Import')path.replaceWith(api.types.callExpression(
        api.types.memberExpression(api.types.identifier('System'),api.types.identifier('import')),
        [path.node.arguments[0],api.types.stringLiteral(filename)]));
    },MetaProperty:function(path){
      if(path.node.meta.name==='import')path.replaceWith(api.types.objectExpression([
        api.types.objectProperty(api.types.identifier('url'),api.types.stringLiteral(filename))
      ]));
    }}};}]
  };
  var special=options.plugins[0],output;
  if(!all)try{options.plugins=[special].concat(selected);output=Babel.transform(source,options).code;acorn.parse(output,{ecmaVersion:5,sourceType:'script'});}catch(uncovered){all=true;}
  if(all){options.plugins=[special];options.presets=[['env',{targets:{chrome:'33'},forceAllTransforms:true,modules:module?'systemjs':false,useBuiltIns:false}]];output=Babel.transform(source,options).code;acorn.parse(output,{ecmaVersion:5,sourceType:'script'});}
  return JSON.stringify({code:output,changed:true,module:module||/System\.register\(/.test(output),syntaxMs:syntaxMs,transformMs:Date.now()-started});
};
