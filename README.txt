PAPELERÍA ABC - CRUD + LOGIN
=============================

REQUISITOS
----------
- Windows Server 2025
- Node.js instalado
- MySQL Server instalado y ejecutándose
- IIS + URL Rewrite + ARR configurados si se utilizará papeleriaabc.com

INSTALACIÓN
-----------
1. Extrae este proyecto, por ejemplo en:

   C:\crud-papeleria

2. Abre PowerShell en esa carpeta:

   cd C:\crud-papeleria

3. Abre server.js y cambia:

   DB_PASSWORD = 'CAMBIA_ESTA_CONTRASENA';

   por la contraseña real del usuario root de MySQL.

4. Instala dependencias:

   npm install

5. Inicia la aplicación:

   npm start


BASE DE DATOS
-------------
No es obligatorio ejecutar database.sql.

Al iniciar Node.js, el programa:
- crea la base de datos papeleria si no existe;
- crea la tabla productos;
- crea la tabla usuarios;
- crea el usuario admin;
- inserta productos de ejemplo si la tabla está vacía.


LOGIN INICIAL
-------------
Usuario:
admin

Contraseña:
Admin123

La contraseña se almacena mediante un hash de bcrypt.


ACCESO DIRECTO
--------------
En el servidor:

http://localhost:3000

o:

http://192.168.18.42:3000


CON IIS + DNS
-------------
Si IIS y DNS están configurados para el dominio:

http://papeleriaabc.com

La solicitud debe seguir:

Windows 10
   |
   v
DNS
   |
   v
192.168.18.42
   |
   v
IIS :80
   |
   v
URL Rewrite + ARR
   |
   v
Node.js :3000
   |
   v
MySQL


FIREWALL
--------
Si se necesita acceso directo al puerto 3000:

New-NetFirewallRule -DisplayName "CRUD Papeleria Node.js 3000" -Direction Inbound -Protocol TCP -LocalPort 3000 -Action Allow


ESTRUCTURA
----------
crud-papeleria/
  server.js
  package.json
  database.sql
  README.txt
  public/
    login.html
    login.js
    index.html
    app.js
    estilos.css


NOTA DE SEGURIDAD
-----------------
Admin123 es una contraseña inicial para fines de práctica.
Para un sistema real debe cambiarse por una contraseña segura y
la clave de sesión debe mantenerse fuera del código fuente.
