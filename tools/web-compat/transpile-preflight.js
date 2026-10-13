// Acorn ES5 accepts Annex B block functions that Chromium 30 rejects in strict code.
// Do not execute website code: inspect strictness and declaration position only.
globalThis.ntvLegacyEs5=function(source,module){
  if(module)return false;
  var ast;try{ast=acorn.parse(source,{ecmaVersion:5,sourceType:'script',allowHashBang:false});}catch(error){return false;}
  if(source.indexOf('use strict')<0)return true;
  function strictBody(body){
    for(var i=0;i<body.length;i++){
      var statement=body[i];
      if(statement.type!=='ExpressionStatement'||typeof statement.directive!=='string')break;
      if(statement.directive==='use strict')return true;
    }
    return false;
  }
  function list(body,strict,allowed){for(var i=0;i<body.length;i++)if(visit(body[i],strict,allowed))return true;return false;}
  function visit(node,strict,allowed){
    if(!node||typeof node!=='object')return false;
    if(Array.isArray(node))return list(node,strict,false);
    if(node.type==='FunctionDeclaration'||node.type==='FunctionExpression'){
      if(node.type==='FunctionDeclaration'&&strict&&!allowed)return true;
      return list(node.body.body,strict||strictBody(node.body.body),true);
    }
    if(node.type==='Program')return list(node.body,strict||strictBody(node.body),true);
    if(node.type==='BlockStatement')return list(node.body,strict,false);
    for(var key in node)if(key!=='start'&&key!=='end'&&visit(node[key],strict,false))return true;
    return false;
  }
  return !visit(ast,false,true);
};
